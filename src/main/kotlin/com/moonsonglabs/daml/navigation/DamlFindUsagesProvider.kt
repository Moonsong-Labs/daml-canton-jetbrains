package com.moonsonglabs.daml.navigation

import com.intellij.lang.findUsages.FindUsagesProvider
import com.intellij.lang.cacheBuilder.DefaultWordsScanner
import com.intellij.lang.cacheBuilder.WordsScanner
import com.intellij.psi.PsiElement
import com.intellij.psi.tree.TokenSet
import com.moonsonglabs.daml.DamlLexer
import com.moonsonglabs.daml.DamlTokenTypes
import com.moonsonglabs.daml.lang.DamlNamedElement

class DamlFindUsagesProvider : FindUsagesProvider {
    override fun getWordsScanner(): WordsScanner = DefaultWordsScanner(DamlLexer(), TokenSet.create(
        DamlTokenTypes.IDENTIFIER, DamlTokenTypes.TYPE_NAME, DamlTokenTypes.PRELUDE_TYPE,
        DamlTokenTypes.BUILTIN_IDENTIFIER, DamlTokenTypes.PREDEFINED_IDENTIFIER, DamlTokenTypes.DAML_KEYWORD),
        DamlTokenTypes.COMMENTS, DamlTokenTypes.STRINGS)
    override fun canFindUsagesFor(psiElement: PsiElement) = psiElement is DamlNamedElement || psiElement.parent is DamlNamedElement || DamlChoiceUsageTargets.fromElement(psiElement) != null
    override fun getHelpId(psiElement: PsiElement): String? = null
    override fun getType(element: PsiElement) = "DAML ${(element as? DamlNamedElement)?.symbol?.kind?.name?.lowercase() ?: "symbol"}"
    override fun getDescriptiveName(element: PsiElement) = (element as? DamlNamedElement)?.name ?: element.text
    override fun getNodeText(element: PsiElement, useFullName: Boolean) = getDescriptiveName(element)
}
