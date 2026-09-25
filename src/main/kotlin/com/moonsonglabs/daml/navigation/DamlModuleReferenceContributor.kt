package com.moonsonglabs.daml.navigation

import com.intellij.openapi.util.TextRange
import com.intellij.patterns.PlatformPatterns
import com.intellij.psi.*
import com.intellij.util.ProcessingContext
import com.moonsonglabs.daml.DamlLanguage
import com.moonsonglabs.daml.DamlFileType
import com.moonsonglabs.daml.lang.DamlNamedElement
import com.moonsonglabs.daml.lang.DamlSourceModel

class DamlModuleReferenceContributor : PsiReferenceContributor() {
    override fun registerReferenceProviders(registrar: PsiReferenceRegistrar) {
        registrar.registerReferenceProvider(PlatformPatterns.psiElement().withLanguage(DamlLanguage), object : PsiReferenceProvider() {
            override fun getReferencesByElement(element: PsiElement, context: ProcessingContext): Array<PsiReference> {
                if (element.firstChild != null || element.parent is DamlNamedElement) return PsiReference.EMPTY_ARRAY
                val file = element.containingFile ?: return PsiReference.EMPTY_ARRAY
                val model = DamlSourceModel.get(file)
                val token = model.tokenAt(element.textOffset)?.takeIf { it.code && it.start == element.textOffset && DamlSourceModel.isName(it.text) } ?: return PsiReference.EMPTY_ARRAY
                val import = model.imports.firstOrNull { token.start in it.moduleStart until it.moduleEnd }
                if (import != null) return arrayOf(object : PsiReferenceBase<PsiElement>(element, TextRange(0, element.textLength), true) {
                    override fun resolve() = DamlModuleResolver.getInstance(element.project).resolve(import.module, file.virtualFile)
                })
                val symbol = DamlModuleResolver.referenceAt(file, token.start) ?: return PsiReference.EMPTY_ARRAY
                return arrayOf(DamlSourceReference(element, symbol))
            }
        })
    }
}

class DamlSourceReference(element: PsiElement, private val symbol: DamlModuleNames.SymbolReference, private val verifiedTarget: PsiElement? = null) :
    PsiPolyVariantReferenceBase<PsiElement>(element, TextRange(0, element.textLength), true) {
    override fun multiResolve(incompleteCode: Boolean): Array<ResolveResult> =
        (verifiedTarget?.let { listOf(it) } ?: DamlModuleResolver.getInstance(element.project).resolveAll(symbol, element.containingFile)).map { PsiElementResolveResult(it) }.toTypedArray()
    override fun handleElementRename(newElementName: String): PsiElement {
        require(DamlSourceModel.isName(newElementName))
        val dummy = PsiFileFactory.getInstance(element.project).createFileFromText("Reference.daml", DamlFileType, "value = $newElementName")
        return element.replace(dummy.findElementAt("value = ".length)!!)
    }
}
