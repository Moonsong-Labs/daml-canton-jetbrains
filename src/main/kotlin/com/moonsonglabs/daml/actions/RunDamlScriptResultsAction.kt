package com.moonsonglabs.daml.actions

import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.psi.PsiManager
import com.intellij.openapi.ui.Messages
import com.moonsonglabs.daml.DamlFileType
import com.moonsonglabs.daml.DamlNotifier
import com.moonsonglabs.daml.scriptresults.DamlScriptResource
import com.moonsonglabs.daml.scriptresults.VirtualResourceManager

class RunDamlScriptResultsAction : AnAction() {
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val file = e.getData(CommonDataKeys.VIRTUAL_FILE) ?: return
        val psiFile = PsiManager.getInstance(project).findFile(file)?.takeIf { it.fileType === DamlFileType }
        if (psiFile == null) {
            DamlNotifier.warn(project, "Open a DAML file to show script results.")
            return
        }

        val offset = e.getData(CommonDataKeys.EDITOR)?.caretModel?.offset ?: 0
        val candidates = DamlScriptResource.findScripts(psiFile.text)
        if (candidates.isEmpty()) {
            DamlNotifier.warn(project, "No DAML script declaration found in ${file.name}.")
            return
        }
        val script = DamlScriptResource.scriptAt(psiFile.text, offset) ?: when (candidates.size) {
            1 -> candidates.single()
            else -> {
                val index = Messages.showChooseDialog(project, "Choose a script for IDE Script Results.", "IDE Script Results", null,
                    candidates.map { it.name }.toTypedArray(), candidates.first().name)
                candidates.getOrNull(index) ?: return
            }
        }

        VirtualResourceManager.getInstance(project).showResource(
            DamlScriptResource.title(script.name),
            DamlScriptResource.uri(file.path, script.name)
        )
    }

    override fun update(e: AnActionEvent) {
        val file = e.getData(CommonDataKeys.VIRTUAL_FILE)
        e.presentation.isEnabledAndVisible =
            e.project != null && file?.fileType === DamlFileType
    }
}
