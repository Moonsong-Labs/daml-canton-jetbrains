package com.moonsonglabs.daml.navigation

import com.intellij.navigation.DirectNavigationProvider
import com.intellij.psi.PsiElement
import com.moonsonglabs.daml.DamlFileType
import com.moonsonglabs.daml.DamlTokenTypes

class DamlDirectNavigationProvider : DirectNavigationProvider {
    override fun getNavigationElement(sourceElement: PsiElement): PsiElement? {
        val file = sourceElement.containingFile ?: return null
        if (file.fileType !== DamlFileType) return null
        val type = sourceElement.node?.elementType

        DamlModuleNames.importAt(file.text, sourceElement.textRange.startOffset)?.let { import ->
            DamlModuleResolver.getInstance(file.project).resolveImport(import, file.virtualFile)?.let { return it }
        }

        val symbol = when {
            type == DamlTokenTypes.OPERATOR && sourceElement.text == "@" ->
                DamlModuleNames.symbolAfterTypeApplicationMarker(file.text, sourceElement.textRange.startOffset)
            type == DamlTokenTypes.TYPE_NAME ||
                type == DamlTokenTypes.PRELUDE_TYPE ||
                type == DamlTokenTypes.IDENTIFIER ->
                DamlModuleNames.symbolAt(file.text, sourceElement.textRange.startOffset)
            else -> null
        } ?: return null

        return DamlModuleResolver.getInstance(file.project)
            .resolveSymbolReference(symbol, file.virtualFile)
    }
}
