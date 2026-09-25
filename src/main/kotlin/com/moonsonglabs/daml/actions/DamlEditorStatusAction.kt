package com.moonsonglabs.daml.actions

import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.components.service
import com.intellij.openapi.ui.Messages
import com.intellij.platform.lsp.api.LspServerManager
import com.moonsonglabs.daml.lsp.DamlLspServerSupportProvider
import com.moonsonglabs.daml.navigation.DamlPackageSources
import org.eclipse.lsp4j.jsonrpc.messages.Either

class DamlEditorStatusAction : AnAction() {
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val file = e.getData(CommonDataKeys.VIRTUAL_FILE)
        val packages = project.service<DamlPackageSources>()
        val servers = LspServerManager.getInstance(project).getServersForProvider(DamlLspServerSupportProvider::class.java)
            .filter { file == null || it.descriptor.isSupportedFile(file) }
        val message = buildString {
            file?.let { appendLine(packages.provenance(it)); appendLine("Package: ${packages.root(it)?.path ?: "No daml.yaml"}") }
            appendLine()
            if (servers.isEmpty()) appendLine("Language server: not running for this file")
            servers.forEach { server ->
                appendLine("Language server: ${server.state}")
                val initialized = server.initializeResult
                appendLine("Compiler reported by server: ${initialized?.serverInfo?.version ?: "Not reported"}")
                val caps = initialized?.capabilities
                if (caps != null) {
                    appendLine("Definition: ${supported(caps.definitionProvider)} · Hover: ${supported(caps.hoverProvider)}")
                    appendLine("Completion: ${caps.completionProvider != null} · References: ${supported(caps.referencesProvider)}")
                    appendLine("Rename: ${supported(caps.renameProvider)} · Implementation: ${supported(caps.implementationProvider)}")
                    val semantic = caps.semanticTokensProvider
                    appendLine("Semantic tokens: ${semantic != null && (supported(semantic.full) || supported(semantic.range))}")
                }
            }
            appendLine()
            appendLine("Native: highlighting, local scopes, imports, structure, symbol search, usages, rename preview, folding.")
            appendLine("Ambiguous references stay unresolved. Field/method rename requires a running compiler and aborts if any occurrence cannot be resolved.")
            appendLine("Dependency navigation uses the selected DAR sources. If a DAR contains no source, compiler navigation may still provide generated declarations.")
        }
        Messages.showInfoMessage(project, message, "DAML Editor Status")
    }
    companion object {
        internal fun supported(value: Either<Boolean, *>?): Boolean = value?.let { if (it.isLeft) it.left == true else it.right != null } ?: false
    }
}
