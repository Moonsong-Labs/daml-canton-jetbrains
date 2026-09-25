package com.moonsonglabs.daml.navigation

import com.intellij.navigation.ChooseByNameContributorEx
import com.intellij.navigation.NavigationItem
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.DumbService
import com.intellij.psi.PsiManager
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.util.Processor
import com.intellij.util.indexing.FileBasedIndex
import com.intellij.util.indexing.FindSymbolParameters
import com.intellij.util.indexing.IdFilter
import com.moonsonglabs.daml.lang.DamlNamedElement
import com.moonsonglabs.daml.lang.DamlSourceModel
import com.moonsonglabs.daml.lang.DamlSymbolIndex

class DamlSymbolContributor : ChooseByNameContributorEx {
    override fun processNames(processor: Processor<in String>, scope: GlobalSearchScope, filter: IdFilter?) {
        val project = scope.project ?: return
        if (DumbService.isDumb(project)) return
        val sourceScope = DamlUsageScope.restrict(project, scope)
        val index = FileBasedIndex.getInstance()
        index.processAllKeys(DamlSymbolIndex.NAME, { key ->
            ProgressManager.checkCanceled()
            !key.startsWith(DamlSymbolIndex.SYMBOL_PREFIX) ||
                index.getContainingFiles(DamlSymbolIndex.NAME, key, sourceScope).isEmpty() ||
                processor.process(key.removePrefix(DamlSymbolIndex.SYMBOL_PREFIX))
        }, sourceScope, filter)
    }
    override fun processElementsWithName(name: String, processor: Processor<in NavigationItem>, parameters: FindSymbolParameters) {
        val project = parameters.project
        if (DumbService.isDumb(project)) return
        val scope = DamlUsageScope.restrict(project, parameters.searchScope)
        for (file in FileBasedIndex.getInstance().getContainingFiles(DamlSymbolIndex.NAME, DamlSymbolIndex.SYMBOL_PREFIX + name, scope)) {
            ProgressManager.checkCanceled()
            val psi = PsiManager.getInstance(project).findFile(file) ?: continue
            for (symbol in DamlSourceModel.get(psi).byName[name].orEmpty()) {
                val target = DamlNamedElement.at(psi, symbol.start) ?: continue
                if (symbol.kind !in DamlSourceModel.PRIVATE_KINDS && !processor.process(target)) return
            }
        }
    }
}
