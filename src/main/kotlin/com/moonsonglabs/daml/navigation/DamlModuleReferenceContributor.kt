package com.moonsonglabs.daml.navigation

import com.intellij.openapi.util.TextRange
import com.intellij.patterns.PlatformPatterns
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiReference
import com.intellij.psi.PsiReferenceBase
import com.intellij.psi.PsiReferenceContributor
import com.intellij.psi.PsiReferenceProvider
import com.intellij.psi.PsiReferenceRegistrar
import com.intellij.util.ProcessingContext
import com.moonsonglabs.daml.DamlLanguage
import com.moonsonglabs.daml.DamlTokenTypes

class DamlModuleReferenceContributor : PsiReferenceContributor() {
    override fun registerReferenceProviders(registrar: PsiReferenceRegistrar) {
        registrar.registerReferenceProvider(
            PlatformPatterns.psiElement().withLanguage(DamlLanguage),
            DamlModuleReferenceProvider()
        )
    }
}

private class DamlModuleReferenceProvider : PsiReferenceProvider() {
    override fun getReferencesByElement(element: PsiElement, context: ProcessingContext): Array<PsiReference> {
        val type = element.node?.elementType
        if (type != DamlTokenTypes.TYPE_NAME &&
            type != DamlTokenTypes.PRELUDE_TYPE &&
            type != DamlTokenTypes.IDENTIFIER &&
            type != DamlTokenTypes.OPERATOR &&
            type != DamlTokenTypes.DOT
        ) {
            return PsiReference.EMPTY_ARRAY
        }
        if (type == DamlTokenTypes.OPERATOR && element.text != "." && element.text != "@") return PsiReference.EMPTY_ARRAY

        val file = element.containingFile ?: return PsiReference.EMPTY_ARRAY
        DamlModuleNames.importAt(file.text, element.textRange.startOffset)?.let { import ->
            val range = if (import.isSymbolReference()) {
                TextRange(import.symbolStartOffset, import.symbolEndOffset)
            } else {
                TextRange(import.startOffset, import.endOffset)
            }
            if (!element.textRange.intersects(range)) {
                return PsiReference.EMPTY_ARRAY
            }
            return arrayOf(DamlModuleReference(element, import))
        }

        if (type == DamlTokenTypes.DOT) return PsiReference.EMPTY_ARRAY
        if (type == DamlTokenTypes.OPERATOR && element.text == "@") {
            val symbol = DamlModuleNames.symbolAfterTypeApplicationMarker(file.text, element.textRange.startOffset)
                ?: return PsiReference.EMPTY_ARRAY
            if (DamlModuleResolver.getInstance(element.project).resolveSymbolReference(symbol, file.virtualFile) == null) {
                return PsiReference.EMPTY_ARRAY
            }
            return arrayOf(DamlSourceSymbolReference(element, symbol))
        }
        if (type == DamlTokenTypes.OPERATOR) return PsiReference.EMPTY_ARRAY
        val symbol = DamlModuleNames.symbolAt(file.text, element.textRange.startOffset)
            ?: return PsiReference.EMPTY_ARRAY
        if (symbol.startOffset != element.textRange.startOffset || symbol.endOffset != element.textRange.endOffset) {
            return PsiReference.EMPTY_ARRAY
        }
        if (DamlModuleResolver.getInstance(element.project).resolveSymbolReference(symbol, file.virtualFile) == null) {
            return PsiReference.EMPTY_ARRAY
        }

        return arrayOf(DamlSourceSymbolReference(element, symbol))
    }
}

private class DamlModuleReference(
    element: PsiElement,
    private val import: DamlModuleNames.ImportReference
) : PsiReferenceBase<PsiElement>(element, TextRange(0, element.textLength), true) {
    override fun resolve(): PsiElement? =
        DamlModuleResolver.getInstance(element.project)
            .resolveImport(import, element.containingFile?.virtualFile)

    override fun getVariants(): Array<Any> =
        DamlModuleResolver.getInstance(element.project).moduleNames().toTypedArray()
}

private class DamlSourceSymbolReference(
    element: PsiElement,
    private val symbol: DamlModuleNames.SymbolReference
) : PsiReferenceBase<PsiElement>(element, TextRange(0, element.textLength), true) {
    override fun resolve(): PsiElement? =
        DamlModuleResolver.getInstance(element.project)
            .resolveSymbolReference(symbol, element.containingFile?.virtualFile)

    override fun getVariants(): Array<Any> =
        DamlModuleResolver.getInstance(element.project).moduleNames().toTypedArray()
}
