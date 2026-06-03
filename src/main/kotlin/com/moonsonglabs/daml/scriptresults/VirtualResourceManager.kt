package com.moonsonglabs.daml.scriptresults

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.platform.lsp.api.LspServerManager
import com.moonsonglabs.daml.lsp.DamlLspServerSupportProvider
import com.moonsonglabs.daml.lsp.DamlServerInterface
import org.eclipse.lsp4j.DidCloseTextDocumentParams
import org.eclipse.lsp4j.DidOpenTextDocumentParams
import org.eclipse.lsp4j.TextDocumentIdentifier
import org.eclipse.lsp4j.TextDocumentItem
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.util.concurrent.ConcurrentHashMap

/**
 * Tracks open DAML virtual-resource URIs (`daml://compiler?file=...&top-level-decl=...`) and
 * dispatches server-pushed `daml/virtualResource/...` notifications to the matching
 * [ScriptResultsPanel].
 *
 * Why only one panel in v1: VSCode opens one webview panel per resource, but a single
 * JetBrains tool window keeps the beta simple and avoids tab lifecycle surprises. We close
 * the previously active virtual resource when a new one is shown.
 */
@Service(Service.Level.PROJECT)
class VirtualResourceManager(private val project: Project) : Disposable {

    private data class Resource(var html: String = "", var notes: List<String> = emptyList(), var progressMs: Long = -1)

    private val resources = ConcurrentHashMap<String, Resource>()
    @Volatile private var activeUri: String? = null

    fun update(uri: String, html: String) {
        resources.computeIfAbsent(uri) { Resource() }.apply {
            this.html = html
            notes = emptyList()
            progressMs = -1
        }
        applyOnEdt(uri) { panel ->
            panel.setTitle(titleFor(uri))
            panel.setProgress(-1)
            panel.setHtml(html)
        }
    }

    fun updateProgress(uri: String, ms: Long) {
        resources.computeIfAbsent(uri) { Resource() }.apply {
            progressMs = ms
            if (ms <= 0) notes = emptyList()
        }
        applyOnEdt(uri) { panel ->
            panel.setTitle(titleFor(uri))
            panel.setProgress(ms)
        }
    }

    fun note(uri: String, html: String) {
        resources.compute(uri) { _, existing ->
            (existing ?: Resource()).apply { notes = notes + html }
        }
        applyOnEdt(uri) { it.setNote(html) }
    }

    /**
     * Bring the panel to the front, rehydrate it for [uri], and notify `damlc ide` that the
     * virtual resource is open. The official VSCode extension also drives Script Results by
     * opening a `daml://compiler?...` document; the server then pushes HTML through
     * `daml/virtualResource/...` notifications.
     */
    fun showResource(title: String, uri: String) {
        openVirtualResource(uri)
        ApplicationManager.getApplication().invokeLater {
            val tw = toolWindow() ?: return@invokeLater
            tw.show()
            val panel = panelOf(tw) ?: return@invokeLater
            panel.setTitle(title)
            resources[uri]?.let {
                if (it.html.isNotEmpty()) panel.setHtml(it.html)
                it.notes.forEach(panel::setNote)
                panel.setProgress(it.progressMs)
            } ?: run {
                panel.clearResource()
                panel.setProgress(-1)
            }
        }
    }

    override fun dispose() {
        activeUri?.let(::closeVirtualResource)
        activeUri = null
    }

    private fun openVirtualResource(uri: String) {
        val previous = activeUri
        if (previous == uri) return
        activeUri = uri
        previous?.let(::closeVirtualResource)
        sendToTextDocumentService(uri, "open") { server ->
            if (activeUri == uri) {
                server.textDocumentService.didOpen(
                    DidOpenTextDocumentParams(TextDocumentItem(uri, "", 0, ""))
                )
            }
        }
    }

    private fun closeVirtualResource(uri: String) {
        sendToTextDocumentService(uri, "close") { server ->
            server.textDocumentService.didClose(
                DidCloseTextDocumentParams(TextDocumentIdentifier(uri))
            )
        }
    }

    private fun sendToTextDocumentService(
        uri: String,
        operation: String,
        block: (DamlServerInterface) -> Unit
    ) {
        if (project.isDisposed) return
        val servers = LspServerManager.getInstance(project)
            .getServersForProvider(DamlLspServerSupportProvider::class.java)
        if (servers.isEmpty()) {
            thisLogger().warn("No DAML language server available to $operation virtual resource $uri")
            return
        }

        servers.forEach { server ->
            server.sendNotification { lsp ->
                if (!project.isDisposed) {
                    try {
                        block(lsp as DamlServerInterface)
                    } catch (t: Throwable) {
                        thisLogger().warn("Failed to $operation DAML virtual resource $uri", t)
                    }
                }
                Unit
            }
        }
    }

    private fun applyOnEdt(uri: String, block: (ScriptResultsPanel) -> Unit) {
        ApplicationManager.getApplication().invokeLater {
            if (activeUri != uri) return@invokeLater
            val tw = toolWindow() ?: return@invokeLater
            if (!tw.isVisible) tw.show()
            val panel = panelOf(tw) ?: return@invokeLater
            block(panel)
        }
    }

    private fun toolWindow(): ToolWindow? =
        ToolWindowManager.getInstance(project).getToolWindow("DAML Script Results")

    private fun panelOf(tw: ToolWindow): ScriptResultsPanel? {
        val content = tw.contentManager.getContent(0) ?: return null
        return content.component as? ScriptResultsPanel
    }

    private fun titleFor(uri: String): String {
        // `daml://compiler?file=foo.daml&top-level-decl=bar`
        val q = uri.substringAfter('?', "")
        val params = q.split('&').mapNotNull {
            val (k, v) = it.split('=', limit = 2).let { p -> if (p.size == 2) p else return@mapNotNull null }
            URLDecoder.decode(k, StandardCharsets.UTF_8) to URLDecoder.decode(v, StandardCharsets.UTF_8)
        }.toMap()
        val decl = params["top-level-decl"]
        val file = params["file"]?.substringAfterLast('/')
        return when {
            decl != null && file != null -> "$decl - $file"
            decl != null -> decl
            file != null -> file
            else -> "DAML Script Results"
        }
    }

    companion object {
        fun getInstance(project: Project): VirtualResourceManager = project.service()
    }
}
