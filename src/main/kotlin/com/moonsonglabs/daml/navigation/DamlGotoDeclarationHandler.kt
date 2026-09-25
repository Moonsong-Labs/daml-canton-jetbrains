package com.moonsonglabs.daml.navigation

import com.intellij.codeInsight.navigation.actions.GotoDeclarationHandler
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiElement
import com.moonsonglabs.daml.DamlFileType

class DamlGotoDeclarationHandler : GotoDeclarationHandler {
    override fun getGotoDeclarationTargets(
        sourceElement: PsiElement?,
        offset: Int,
        editor: Editor
    ): Array<PsiElement>? {
        val file = sourceElement?.containingFile ?: return null
        if (file.fileType !== DamlFileType) return null
        DamlModuleNames.importAt(file.text, offset)?.let { import ->
            DamlModuleResolver.getInstance(file.project).resolveImport(import, file.virtualFile)?.let { target ->
                return arrayOf(target)
            }
        }
        return symbolTargets(file.text, offset, file.virtualFile, sourceElement)
    }

    override fun getActionText(context: DataContext): String =
        "Go to DAML Declaration"

    private fun symbolTargets(
        text: String,
        offset: Int,
        contextFile: VirtualFile?,
        sourceElement: PsiElement
    ): Array<PsiElement>? {
        val file = sourceElement.containingFile
        val model = com.moonsonglabs.daml.lang.DamlSourceModel.get(file)
        val actual = if (model.text.getOrNull(offset) == '@') offset + 1 else offset
        val symbol = DamlModuleResolver.referenceAt(file, actual) ?: return null
        return DamlModuleResolver.getInstance(sourceElement.project).resolveAll(symbol, file)
            .takeIf { it.isNotEmpty() }?.toTypedArray()
    }
}
