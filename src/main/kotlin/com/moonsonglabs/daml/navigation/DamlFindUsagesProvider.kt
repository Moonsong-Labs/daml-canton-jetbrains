package com.moonsonglabs.daml.navigation

import com.intellij.lang.findUsages.FindUsagesProvider
import com.intellij.lang.cacheBuilder.WordsScanner
import com.intellij.psi.PsiElement

class DamlFindUsagesProvider : FindUsagesProvider {
    override fun getWordsScanner(): WordsScanner? = null

    override fun canFindUsagesFor(psiElement: PsiElement): Boolean =
        choiceTarget(psiElement) != null

    override fun getHelpId(psiElement: PsiElement): String? = null

    override fun getType(element: PsiElement): String =
        if (choiceTarget(element) != null) "DAML choice" else "DAML symbol"

    override fun getDescriptiveName(element: PsiElement): String =
        choiceTarget(element)?.name ?: element.text

    override fun getNodeText(element: PsiElement, useFullName: Boolean): String =
        getDescriptiveName(element)

    private fun choiceTarget(element: PsiElement): DamlChoiceUsageTarget? =
        DamlChoiceUsageTargets.fromElement(element)
}
