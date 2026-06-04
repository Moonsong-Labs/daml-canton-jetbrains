package com.moonsonglabs.daml.scriptresults

import com.google.gson.Gson
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.editor.ScrollType
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.ui.jcef.JBCefApp
import com.intellij.ui.jcef.JBCefBrowser
import com.intellij.ui.jcef.JBCefBrowserBase
import com.intellij.ui.jcef.JBCefJSQuery
import com.moonsonglabs.daml.DamlBundle
import com.moonsonglabs.daml.settings.DamlProjectSettings
import org.cef.browser.CefBrowser
import org.cef.handler.CefLoadHandlerAdapter
import java.awt.BorderLayout
import java.awt.Color
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.nio.file.Paths
import java.util.Base64
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.SwingConstants
import javax.swing.UIManager

/**
 * The Script Results panel.
 *
 * Renders server-pushed HTML in a JCEF browser, exposing the same host/webview bridge that
 * VSCode's webview uses (`set_show_archived`, `set_show_detailed_disclosure`,
 * `set_selected_view`, plus host-to-guest `set_view`/`add_note`) and adapting VSCode's
 * `command:daml.revealLocation` source links for JetBrains.
 *
 * If JCEF is unavailable on this IDE (e.g. on Linux without the JCEF runtime), falls back
 * to a plain-text label so the panel doesn't crash.
 */
class ScriptResultsPanel(private val project: Project) : JPanel(BorderLayout()), Disposable {

    private val browser: JBCefBrowser?
    private val jsQuery: JBCefJSQuery?
    private val gson = Gson()
    private var webviewReady = false
    private var latestHtml: String? = null
    private var latestProgress: Long? = null
    private val pendingNotes = mutableListOf<String>()
    private var titleLabel = JLabel("DAML Script Results", SwingConstants.LEFT).apply {
        border = javax.swing.BorderFactory.createEmptyBorder(4, 8, 4, 8)
    }

    init {
        if (!JBCefApp.isSupported()) {
            browser = null
            jsQuery = null
            background = UIManager.getColor("Panel.background") ?: Color.WHITE
            add(JLabel(DamlBundle.message("daml.notification.jcef.unavailable"),
                SwingConstants.CENTER), BorderLayout.CENTER)
        } else {
            val b = JBCefBrowser()
            browser = b
            // Tie native CEF browser + JS query lifetimes to this panel so they're released
            // when the tool window content is removed (project close, plugin reload, etc).
            Disposer.register(this, b)
            val q = JBCefJSQuery.create(b as JBCefBrowserBase)
            jsQuery = q
            Disposer.register(this, q)
            q.addHandler { raw -> handleHostMessage(raw); null }
            b.jbCefClient.addLoadHandler(object : CefLoadHandlerAdapter() {
                override fun onLoadEnd(cefBrowser: CefBrowser?, frame: org.cef.browser.CefFrame?, httpStatusCode: Int) {
                    installBridge(cefBrowser)
                    webviewReady = true
                    sendInitialView()
                    flushPendingMessages()
                }
            }, b.cefBrowser)
            add(titleLabel, BorderLayout.NORTH)
            add(b.component, BorderLayout.CENTER)
            loadInitialHtml()
        }
    }

    override fun dispose() {
        // Children registered via Disposer.register are torn down automatically.
    }

    fun setTitle(title: String) {
        titleLabel.text = title
    }

    fun setHtml(html: String) {
        if (browser == null) return
        latestHtml = html
        pendingNotes.clear()
        if (webviewReady) dispatchHtml(html)
    }

    fun setNote(html: String) {
        if (browser == null) return
        if (webviewReady) {
            dispatchNote(html)
        } else {
            pendingNotes.add(html)
        }
    }

    fun setProgress(millisecondsPassed: Long) {
        if (browser == null) return
        latestProgress = millisecondsPassed
        if (webviewReady) dispatchProgress(millisecondsPassed)
    }

    fun clearResource() {
        latestHtml = null
        latestProgress = null
        pendingNotes.clear()
        if (webviewReady) {
            dispatchHtml("")
            dispatchProgress(-1)
        }
    }

    private fun loadInitialHtml() {
        val htmlBytes = javaClass.getResource("/webview/webview.html")?.readBytes() ?: return
        val js = String(javaClass.getResource("/webview/webview.js")!!.readBytes(), StandardCharsets.UTF_8)
        val css = String(javaClass.getResource("/webview/webview.css")!!.readBytes(), StandardCharsets.UTF_8)
        val jsDataUrl = "data:application/javascript;base64," +
            Base64.getEncoder().encodeToString(js.toByteArray(StandardCharsets.UTF_8))
        val cssDataUrl = "data:text/css;base64," +
            Base64.getEncoder().encodeToString(css.toByteArray(StandardCharsets.UTF_8))

        val html = String(htmlBytes, StandardCharsets.UTF_8)
            .replace("\$webviewSrc", jsDataUrl)
            .replace("\$webviewCss", cssDataUrl)
            .replace("\$webviewTheme", webviewThemeClass())
        browser?.loadHTML(html)
    }

    private fun installBridge(cefBrowser: CefBrowser?) {
        val q = jsQuery ?: return
        val script = """
            window.jbBridge = {
                postMessage: function(msg) { ${q.inject("msg")} }
            };
        """.trimIndent()
        cefBrowser?.executeJavaScript(script, cefBrowser.url, 0)
    }

    private fun sendInitialView() {
        val s = DamlProjectSettings.getInstance(project)
        val msg = mapOf(
            "command" to "set_view",
            "value" to mapOf(
                "selected" to s.selectedView,
                "showArchived" to s.showArchived,
                "showDetailedDisclosure" to s.showDetailedDisclosure,
                "theme" to webviewThemeClass()
            )
        )
        postToWebview(msg)
    }

    private fun flushPendingMessages() {
        latestHtml?.let(::dispatchHtml)
        pendingNotes.forEach(::dispatchNote)
        pendingNotes.clear()
        latestProgress?.let(::dispatchProgress)
    }

    private fun dispatchHtml(html: String) {
        val b = browser ?: return
        val js = "if (window.setHtmlContent) setHtmlContent(${gson.toJson(html)});"
        b.cefBrowser.executeJavaScript(js, b.cefBrowser.url, 0)
    }

    private fun dispatchNote(html: String) {
        val msg = mapOf("command" to "add_note", "value" to html)
        postToWebview(msg)
    }

    private fun dispatchProgress(millisecondsPassed: Long) {
        val msg = mapOf("command" to "set_progress", "value" to millisecondsPassed)
        postToWebview(msg)
    }

    private fun webviewThemeClass(): String {
        val color = UIManager.getColor("Panel.background") ?: background ?: Color.WHITE
        val luminance = 0.2126 * color.red + 0.7152 * color.green + 0.0722 * color.blue
        return if (luminance < 128) "ide-dark" else "ide-light"
    }

    private fun postToWebview(msg: Map<String, Any?>) {
        val b = browser ?: return
        val payload = gson.toJson(msg)
        val js = "window.dispatchEvent(new MessageEvent('message', { data: $payload }));"
        b.cefBrowser.executeJavaScript(js, b.cefBrowser.url, 0)
    }

    private fun handleHostMessage(raw: String) {
        try {
            @Suppress("UNCHECKED_CAST")
            val map = gson.fromJson(raw, Map::class.java) as Map<String, Any?>
            val s = DamlProjectSettings.getInstance(project)
            when (map["command"] as? String) {
                "set_show_archived" -> s.showArchived = (map["value"] as? Boolean) ?: false
                "set_show_detailed_disclosure" -> s.showDetailedDisclosure = (map["value"] as? Boolean) ?: false
                "set_selected_view" -> s.selectedView = (map["value"] as? String) ?: "overview"
                "reveal_location" -> revealLocation(map["value"] as? String)
                else -> thisLogger().debug("Unhandled host message: $raw")
            }
        } catch (t: Throwable) {
            thisLogger().warn("Failed to handle host message: $raw", t)
        }
    }

    private fun revealLocation(commandUri: String?) {
        val args = parseRevealLocationArgs(commandUri) ?: return
        val file = VirtualFileManager.getInstance().findFileByUrl(args.uri) ?: return
        if (!isRevealTargetAllowed(file)) return
        ApplicationManager.getApplication().invokeLater {
            val line = args.startLine.coerceAtLeast(0)
            val editor = FileEditorManager.getInstance(project).openTextEditor(
                OpenFileDescriptor(project, file, line, 0),
                true
            )
            if (editor != null && editor.document.lineCount > 0) {
                val startLine = args.startLine.coerceIn(0, editor.document.lineCount - 1)
                val endLine = args.endLine.coerceIn(startLine, editor.document.lineCount - 1)
                val startOffset = editor.document.getLineStartOffset(startLine)
                val endOffset = editor.document.getLineEndOffset(endLine)
                editor.caretModel.moveToOffset(startOffset)
                editor.selectionModel.setSelection(startOffset, endOffset)
                editor.scrollingModel.scrollToCaret(ScrollType.CENTER)
            }
        }
    }

    private fun isRevealTargetAllowed(file: com.intellij.openapi.vfs.VirtualFile): Boolean {
        if (file.extension != "daml") return false
        if (file.path.split('/').any { it == ".daml" }) return false
        val basePath = project.basePath ?: return true
        val base = Paths.get(basePath).normalize()
        val target = runCatching { file.toNioPath().normalize() }.getOrNull() ?: return false
        return target.startsWith(base)
    }

    private fun parseRevealLocationArgs(commandUri: String?): RevealLocationArgs? {
        if (commandUri == null || !commandUri.startsWith("command:daml.revealLocation")) return null
        val encodedArgs = commandUri.substringAfter('?', "")
        if (encodedArgs.isBlank()) return null
        val decoded = URLDecoder.decode(encodedArgs, StandardCharsets.UTF_8)
        val values = gson.fromJson(decoded, List::class.java)
        val uri = values.getOrNull(0) as? String ?: return null
        val startLine = (values.getOrNull(1) as? Number)?.toInt() ?: 0
        val endLine = (values.getOrNull(2) as? Number)?.toInt() ?: startLine
        return RevealLocationArgs(uri, startLine, endLine)
    }

    private data class RevealLocationArgs(val uri: String, val startLine: Int, val endLine: Int)
}
