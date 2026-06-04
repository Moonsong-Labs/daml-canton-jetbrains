package com.moonsonglabs.daml.lsp

import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.project.Project
import com.moonsonglabs.daml.scriptresults.VirtualResourceManager
import com.intellij.platform.lsp.api.Lsp4jClient
import com.intellij.platform.lsp.api.LspServerNotificationsHandler
import org.eclipse.lsp4j.jsonrpc.services.JsonNotification

/**
 * Custom DAML LanguageClient.
 *
 * Responsibilities:
 *  - Handle DAML's non-standard server→client notifications (the `daml/virtualResource/...`
 *    and `daml/sdkInstall/...` families) so the Script Results panel can stay in sync.
 */
class DamlLanguageClient(
    private val project: Project,
    serverNotificationsHandler: LspServerNotificationsHandler
) : Lsp4jClient(serverNotificationsHandler) {

    // ---- DAML virtual-resource server-pushed notifications ----

    @JsonNotification("daml/virtualResource/didChange")
    fun virtualResourceDidChange(payload: Map<String, Any?>) {
        val uri = payload["uri"] as? String ?: return
        val contents = payload["contents"] as? String ?: return
        VirtualResourceManager.getInstance(project).update(uri, contents)
    }

    @JsonNotification("daml/virtualResource/didProgress")
    fun virtualResourceDidProgress(payload: Map<String, Any?>) {
        val uri = payload["uri"] as? String ?: return
        val ms = (payload["millisecondsPassed"] as? Number)?.toLong() ?: return
        VirtualResourceManager.getInstance(project).updateProgress(uri, ms)
    }

    @JsonNotification("daml/virtualResource/note")
    fun virtualResourceNote(payload: Map<String, Any?>) {
        val uri = payload["uri"] as? String ?: return
        val note = (payload["note"] ?: payload["contents"] ?: payload["message"]) as? String ?: return
        VirtualResourceManager.getInstance(project).note(uri, note)
    }

    @JsonNotification("daml/sdkInstall/progress")
    fun sdkInstallProgress(payload: Map<String, Any?>) {
        thisLogger().debug("daml/sdkInstall/progress: $payload")
    }
}
