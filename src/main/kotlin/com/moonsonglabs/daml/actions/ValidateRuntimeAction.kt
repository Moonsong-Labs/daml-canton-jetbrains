package com.moonsonglabs.daml.actions

import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.Task
import com.moonsonglabs.daml.DamlNotifier
import com.moonsonglabs.daml.runtime.RuntimeValidator

class ValidateRuntimeAction : AnAction() {
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        object : Task.Backgroundable(project, "Validating Canton/DAML Runtime", false) {
            override fun run(indicator: ProgressIndicator) {
                val result = RuntimeValidator.getInstance(project).validate()
                val details = result.checks.joinToString("<br/>") {
                    "${if (it.ok) "OK" else "MISSING"} ${it.name}: ${it.detail}"
                }
                val message = "${result.message}<br/><br/>$details"
                if (result.ok) DamlNotifier.info(project, message) else DamlNotifier.warn(project, message)
            }
        }.queue()
    }

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabled = e.project != null
    }
}
