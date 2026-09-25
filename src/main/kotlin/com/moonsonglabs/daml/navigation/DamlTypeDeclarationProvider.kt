package com.moonsonglabs.daml.navigation

import com.intellij.codeInsight.navigation.actions.TypeDeclarationProvider
import com.intellij.psi.PsiElement
import com.moonsonglabs.daml.lang.DamlNamedElement
import com.moonsonglabs.daml.lang.DamlSourceModel

/** Navigate explicit annotations only; inferred application/record types remain compiler-owned. */
class DamlTypeDeclarationProvider : TypeDeclarationProvider {
    override fun getSymbolTypeDeclarations(symbol: PsiElement): Array<PsiElement>? {
        val file = symbol.containingFile ?: return null
        if (file.fileType != com.moonsonglabs.daml.DamlFileType) return null
        val resolver = DamlModuleResolver.getInstance(symbol.project)
        val target = (symbol as? DamlNamedElement) ?: (symbol.parent as? DamlNamedElement)
            ?: DamlModuleResolver.referenceAt(file, symbol.textOffset)?.let { resolver.resolveAll(it, file).singleOrNull() as? DamlNamedElement } ?: return null
        val annotation = target.symbol?.signature ?: return null
        if ("->" in annotation || ' ' in annotation.trim()) return null
        val model = DamlSourceModel.get(target.containingFile)
        val token = model.lineAt(target.textOffset).tokens.firstOrNull { it.start > target.textOffset && it.text == annotation.trim() } ?: return null
        val reference = DamlModuleResolver.referenceAt(target.containingFile, token.start) ?: return null
        return resolver.resolveAll(reference, target.containingFile).takeIf { it.isNotEmpty() }?.toTypedArray()
    }
}
