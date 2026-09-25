package com.moonsonglabs.daml.navigation

import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.components.service
import com.moonsonglabs.daml.lang.DamlSourceModel
import com.intellij.psi.PsiReference
import com.intellij.psi.search.PsiSearchHelper
import com.intellij.psi.search.UsageSearchContext
import com.intellij.psi.search.searches.ReferencesSearch
import com.intellij.util.Processor
import com.intellij.util.QueryExecutor
import com.moonsonglabs.daml.lang.DamlNamedElement

class DamlSymbolReferencesSearch : QueryExecutor<PsiReference, ReferencesSearch.SearchParameters> {
    override fun execute(parameters: ReferencesSearch.SearchParameters, consumer: Processor<in PsiReference>): Boolean {
        val target = (parameters.elementToSearch as? DamlNamedElement) ?: (parameters.elementToSearch.parent as? DamlNamedElement) ?: (DamlChoiceUsageTargets.fromElement(parameters.elementToSearch)?.element as? DamlNamedElement) ?: return true
        return PsiSearchHelper.getInstance(target.project).processElementsWithWord({ element, offset ->
            ProgressManager.checkCanceled()
            val file = element.containingFile
            if (!DamlUsageScope.accepts(target.project, file.virtualFile)) return@processElementsWithWord true
            val absolute = element.textRange.startOffset + offset
            val leaf = file.findElementAt(absolute)
            val symbol = DamlModuleResolver.referenceAt(file, absolute)
            if (leaf == null || symbol == null || leaf.parent == target) true else {
                val reference = DamlSourceReference(leaf, symbol)
                if (reference.resolve() == target) consumer.process(reference)
                else if (target.symbol?.kind in setOf(DamlSourceModel.Kind.FIELD, DamlSourceModel.Kind.METHOD) &&
                    element.project.service<DamlCompilerDefinitions>().resolve(leaf)?.any { it == target } == true) {
                    consumer.process(DamlSourceReference(leaf, symbol, target))
                } else true
            }
        }, DamlUsageScope.restrict(target.project, parameters.effectiveSearchScope), target.name, UsageSearchContext.IN_CODE, true)
    }
}
