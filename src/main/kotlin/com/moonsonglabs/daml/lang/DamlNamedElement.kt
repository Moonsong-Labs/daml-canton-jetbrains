package com.moonsonglabs.daml.lang

import com.intellij.extapi.psi.ASTWrapperPsiElement
import com.intellij.lang.ASTNode
import com.intellij.navigation.ItemPresentation
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFileFactory
import com.intellij.psi.PsiNameIdentifierOwner
import com.intellij.psi.PsiReference
import com.intellij.psi.PsiReferenceService
import com.moonsonglabs.daml.DamlFileType
import com.moonsonglabs.daml.DamlIcons

class DamlNamedElement(node: ASTNode) : ASTWrapperPsiElement(node), PsiNameIdentifierOwner {
    override fun getUseScope(): com.intellij.psi.search.SearchScope =
        com.moonsonglabs.daml.navigation.DamlUsageScope.restrict(project, super.getUseScope())
    override fun getName(): String = text
    override fun getNameIdentifier(): PsiElement = firstChild
    override fun getTextOffset(): Int = nameIdentifier.textOffset
    override fun setName(name: String): PsiElement {
        require(DamlSourceModel.isName(name)) { "Not a DAML identifier: $name" }
        val prefix = if (name.first().isUpperCase()) "data " else ""
        val suffix = if (prefix.isEmpty()) " = undefined" else " = Placeholder"
        val replacement = PsiFileFactory.getInstance(project).createFileFromText("Name.daml", DamlFileType, prefix + name + suffix)
            .findElementAt(prefix.length)?.parent as? DamlNamedElement ?: error("Cannot create DAML identifier")
        return replace(replacement)
    }
    override fun getPresentation(): ItemPresentation = object : ItemPresentation {
        override fun getPresentableText() = name
        override fun getLocationString() = DamlSourceModel.get(containingFile).module ?: containingFile.name
        override fun getIcon(unused: Boolean) = DamlIcons.File
    }
    val symbol: DamlSourceModel.Symbol? get() = DamlSourceModel.get(containingFile).declaration(textOffset)
    companion object {
        fun at(file: com.intellij.psi.PsiFile, offset: Int): DamlNamedElement? =
            file.findElementAt(offset)?.parent as? DamlNamedElement
    }
}
