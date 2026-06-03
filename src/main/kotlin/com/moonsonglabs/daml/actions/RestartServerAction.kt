package com.moonsonglabs.daml.actions

import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.platform.lsp.api.LspServerManager
import com.moonsonglabs.daml.DamlBundle
import com.moonsonglabs.daml.DamlNotifier
import com.moonsonglabs.daml.lsp.DamlLspServerSupportProvider

class RestartServerAction : AnAction() {
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        try {
            LspServerManager.getInstance(project)
                .stopAndRestartIfNeeded(DamlLspServerSupportProvider::class.java)
            DamlNotifier.info(project, DamlBundle.message("daml.action.restart.success"))
        } catch (t: Throwable) {
            DamlNotifier.warn(project, DamlBundle.message("daml.action.restart.notRunning"))
        }
    }

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabled = e.project != null
    }
}
