package com.moonsonglabs.daml.editor

import com.intellij.lang.documentation.AbstractDocumentationProvider
import com.intellij.openapi.editor.Editor
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.moonsonglabs.daml.DamlFileType
import com.moonsonglabs.daml.lang.DamlNamedElement
import com.moonsonglabs.daml.navigation.DamlModuleResolver

class DamlDocumentationProvider : AbstractDocumentationProvider() {
    override fun generateDoc(element: PsiElement?, originalElement: PsiElement?): String? =
        (target(element) ?: target(originalElement))?.let(DamlDocumentation::render)

    override fun getQuickNavigateInfo(element: PsiElement?, originalElement: PsiElement?) = generateDoc(element, originalElement)

    override fun getCustomDocumentationElement(editor: Editor, file: PsiFile, contextElement: PsiElement?, targetOffset: Int): PsiElement? =
        targetAt(file, targetOffset)

    companion object {
        fun targetAt(file: PsiFile, offset: Int): DamlNamedElement? {
            if (file.fileType != DamlFileType) return null
            DamlNamedElement.at(file, offset)?.takeIf { it.symbol != null }?.let { return it }
            val reference = DamlModuleResolver.referenceAt(file, offset) ?: return null
            return DamlModuleResolver.getInstance(file.project).resolveAll(reference, file).singleOrNull() as? DamlNamedElement
        }

        private fun target(element: PsiElement?): DamlNamedElement? {
            if (element == null) return null
            return targetAt(element.containingFile ?: return null, element.textOffset)
        }
    }
}
