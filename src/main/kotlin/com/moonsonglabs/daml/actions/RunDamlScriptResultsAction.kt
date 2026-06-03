package com.moonsonglabs.daml.actions

import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.psi.PsiManager
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
        val script = DamlScriptResource.scriptAt(psiFile.text, offset)
        if (script == null) {
            DamlNotifier.warn(project, "No DAML script declaration found in ${file.name}.")
            return
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
