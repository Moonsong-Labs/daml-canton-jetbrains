package com.moonsonglabs.daml.navigation

import com.moonsonglabs.daml.lang.DamlSourceModel

object DamlLocalBindings {
    data class Binding(val name: String, val startOffset: Int, val endOffset: Int)
    fun resolve(text: String, reference: DamlModuleNames.SymbolReference): Binding? {
        if (reference.qualifier != null) return null
        return DamlSourceModel.parse(text).local(reference.name, reference.startOffset)
            ?.takeIf { it.start != reference.startOffset }
            ?.let { Binding(it.name, it.start, it.start + it.name.length) }
    }
}
