package com.moonsonglabs.daml.editor

import com.intellij.codeInsight.codeVision.CodeVisionRelativeOrdering
import com.intellij.codeInsight.hints.codeVision.CodeVisionProviderBase
import com.intellij.codeInsight.daemon.DaemonCodeAnalyzer
import com.intellij.find.FindManager
import com.intellij.ide.util.PropertiesComponent
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.ToggleAction
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.DumbService
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.PsiSearchHelper
import com.intellij.psi.search.searches.ReferencesSearch
import com.moonsonglabs.daml.DamlFileType
import com.moonsonglabs.daml.lang.DamlNamedElement
import com.moonsonglabs.daml.lang.DamlSourceModel
import java.awt.event.MouseEvent

class DamlUsageCodeVision : CodeVisionProviderBase() {
    override val id = SETTING
    override val name = "DAML resolved usages"
    override val relativeOrderings: List<CodeVisionRelativeOrdering> = emptyList()
    override fun acceptsFile(file: PsiFile) = file.fileType == DamlFileType && !DumbService.isDumb(file.project) && PropertiesComponent.getInstance(file.project).getBoolean(SETTING)
    override fun acceptsElement(element: PsiElement) = element is DamlNamedElement && element.symbol?.kind !in DamlSourceModel.PRIVATE_KINDS + setOf(DamlSourceModel.Kind.FIELD, DamlSourceModel.Kind.METHOD)
    override fun getHint(element: PsiElement, file: PsiFile): String? {
        val target = element as? DamlNamedElement ?: return null
        val helper = PsiSearchHelper.getInstance(file.project)
        if (helper.isCheapEnoughToSearch(target.name, GlobalSearchScope.projectScope(file.project), file) == PsiSearchHelper.SearchCostResult.TOO_MANY_OCCURRENCES) return null
        val count = ReferencesSearch.search(target).findAll().count { DamlSourceModel.get(it.element.containingFile).declaration(it.element.textOffset) == null }
        return "$count resolved ${if (count == 1) "usage" else "usages"}"
    }
    override fun handleClick(editor: Editor, element: PsiElement, event: MouseEvent?) { FindManager.getInstance(element.project).findUsages(element) }
    companion object { const val SETTING = "daml.editor.usage.counts" }
}
class DamlToggleUsageCounts : ToggleAction() {
    override fun isSelected(e: AnActionEvent) = e.project?.let { PropertiesComponent.getInstance(it).getBoolean(DamlUsageCodeVision.SETTING) } ?: false
    override fun setSelected(e: AnActionEvent, state: Boolean) {
        val project = e.project ?: return
        PropertiesComponent.getInstance(project).setValue(DamlUsageCodeVision.SETTING, state)
        DaemonCodeAnalyzer.getInstance(project).restart()
    }
}
