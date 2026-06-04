package com.moonsonglabs.daml.navigation

import com.intellij.find.findUsages.FindUsagesHandler
import com.intellij.find.findUsages.FindUsagesHandlerFactory
import com.intellij.psi.PsiElement

class DamlChoiceFindUsagesHandlerFactory : FindUsagesHandlerFactory() {
    override fun canFindUsages(element: PsiElement): Boolean =
        DamlChoiceUsageTargets.fromElement(element) != null

    override fun createFindUsagesHandler(element: PsiElement, forHighlightUsages: Boolean): FindUsagesHandler? =
        DamlChoiceUsageTargets.fromElement(element)?.let { DamlChoiceFindUsagesHandler(it.element) }

    private class DamlChoiceFindUsagesHandler(element: PsiElement) : FindUsagesHandler(element)
}
