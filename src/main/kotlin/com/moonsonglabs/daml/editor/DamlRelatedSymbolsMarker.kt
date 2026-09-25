package com.moonsonglabs.daml.editor

import com.intellij.codeInsight.daemon.RelatedItemLineMarkerInfo
import com.intellij.codeInsight.daemon.RelatedItemLineMarkerProvider
import com.intellij.codeInsight.navigation.NavigationGutterIconBuilder
import com.intellij.icons.AllIcons
import com.intellij.openapi.project.DumbService
import com.intellij.psi.PsiElement
import com.intellij.psi.search.searches.DefinitionsScopedSearch
import com.moonsonglabs.daml.lang.DamlNamedElement
import com.moonsonglabs.daml.lang.DamlSourceModel
import com.moonsonglabs.daml.navigation.DamlModuleResolver

class DamlRelatedSymbolsMarker : RelatedItemLineMarkerProvider() {
    override fun getName() = "DAML interfaces and implementations"
    override fun collectNavigationMarkers(element: PsiElement, result: MutableCollection<in RelatedItemLineMarkerInfo<*>>) {
        if (element.firstChild != null || DumbService.isDumb(element.project)) return
        val named = element.parent as? DamlNamedElement ?: return
        val symbol = named.symbol ?: return
        val model = DamlSourceModel.get(named.containingFile)
        val targets = if (symbol.kind == DamlSourceModel.Kind.TEMPLATE) {
            model.symbols.filter { it.owner == symbol.start && it.kind == DamlSourceModel.Kind.INSTANCE }.flatMap {
                val ref = DamlModuleResolver.referenceAt(named.containingFile, it.start)
                if (ref == null) emptyList() else DamlModuleResolver.getInstance(named.project).resolveAll(ref, named.containingFile)
            }
        } else if (symbol.kind in setOf(DamlSourceModel.Kind.INTERFACE, DamlSourceModel.Kind.METHOD)) {
            DefinitionsScopedSearch.search(named).findAll().toList()
        } else emptyList()
        if (targets.isEmpty()) return
        result.add(NavigationGutterIconBuilder.create(AllIcons.Gutter.ImplementedMethod).setTargets(targets)
            .setTooltipText("Related DAML interfaces / implementations").setPopupTitle("DAML Related Symbols").createLineMarkerInfo(element))
    }
}
