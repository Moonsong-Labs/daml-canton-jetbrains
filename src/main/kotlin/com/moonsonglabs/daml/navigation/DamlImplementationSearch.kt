package com.moonsonglabs.daml.navigation

import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.DumbService
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiManager
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.PsiSearchScopeUtil
import com.intellij.psi.search.searches.DefinitionsScopedSearch
import com.intellij.util.Processor
import com.intellij.util.QueryExecutor
import com.intellij.util.indexing.FileBasedIndex
import com.moonsonglabs.daml.lang.DamlNamedElement
import com.moonsonglabs.daml.lang.DamlSourceModel
import com.moonsonglabs.daml.lang.DamlSymbolIndex

class DamlImplementationSearch : QueryExecutor<PsiElement, DefinitionsScopedSearch.SearchParameters> {
    override fun execute(parameters: DefinitionsScopedSearch.SearchParameters, consumer: Processor<in PsiElement>): Boolean {
        val named = (parameters.element as? DamlNamedElement) ?: (parameters.element.parent as? DamlNamedElement) ?: return true
        val symbol = named.symbol ?: return true
        val model = DamlSourceModel.get(named.containingFile)
        val owner = if (symbol.kind == DamlSourceModel.Kind.INTERFACE) symbol else model.owner(symbol)?.takeIf { it.kind == DamlSourceModel.Kind.INTERFACE } ?: return true
        if (DumbService.isDumb(named.project)) return true
        val scope = parameters.scope as? GlobalSearchScope ?: GlobalSearchScope.projectScope(named.project)
        val files = FileBasedIndex.getInstance().getContainingFiles(DamlSymbolIndex.NAME, DamlSymbolIndex.IMPLEMENTATION_PREFIX + owner.name, scope)
        for (file in files) {
            ProgressManager.checkCanceled()
            if (!DamlUsageScope.accepts(named.project, file)) continue
            val psi = PsiManager.getInstance(named.project).findFile(file) ?: continue
            val source = DamlSourceModel.get(psi)
            for (instance in source.symbols.filter { it.kind == DamlSourceModel.Kind.INSTANCE && it.name == owner.name }) {
                val ref = DamlModuleResolver.referenceAt(psi, instance.start) ?: continue
                val resolved = DamlModuleResolver.getInstance(named.project).resolveAll(ref, psi).singleOrNull() ?: continue
                if (resolved.containingFile != named.containingFile || resolved.textOffset != owner.start) continue
                val destination = if (symbol.kind == DamlSourceModel.Kind.INTERFACE) source.owner(instance) else source.symbols.firstOrNull { it.owner == instance.start && it.name == symbol.name }
                val target = destination?.let { DamlNamedElement.at(psi, it.start) } ?: continue
                if (PsiSearchScopeUtil.isInScope(parameters.scope, target) && !consumer.process(target)) return false
            }
        }
        return true
    }
}
