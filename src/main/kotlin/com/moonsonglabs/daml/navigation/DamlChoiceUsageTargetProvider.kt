package com.moonsonglabs.daml.navigation

import com.intellij.find.findUsages.PsiElement2UsageTargetAdapter
import com.intellij.openapi.editor.Editor
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.usages.UsageTarget
import com.intellij.usages.UsageTargetProvider

class DamlChoiceUsageTargetProvider : UsageTargetProvider {
    override fun getTargets(editor: Editor, file: PsiFile): Array<UsageTarget>? =
        DamlChoiceUsageTargets.fromFileOffset(file, editor.caretModel.offset)?.toTargets()

    override fun getTargets(psiElement: PsiElement): Array<UsageTarget>? =
        DamlChoiceUsageTargets.fromElement(psiElement)?.toTargets()

    private fun DamlChoiceUsageTarget.toTargets(): Array<UsageTarget> =
        arrayOf(PsiElement2UsageTargetAdapter(element, false))
}
