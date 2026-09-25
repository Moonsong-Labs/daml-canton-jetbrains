package com.moonsonglabs.daml.lang

import com.intellij.util.indexing.DataIndexer
import com.intellij.util.indexing.DefaultFileTypeSpecificInputFilter
import com.intellij.util.indexing.FileBasedIndex
import com.intellij.util.indexing.FileContent
import com.intellij.util.indexing.ID
import com.intellij.util.indexing.ScalarIndexExtension
import com.intellij.util.io.EnumeratorStringDescriptor
import com.intellij.util.io.KeyDescriptor
import com.moonsonglabs.daml.DamlFileType

class DamlSymbolIndex : ScalarIndexExtension<String>() {
    override fun getName(): ID<String, Void> = NAME
    override fun getVersion() = 2
    override fun dependsOnFileContent() = true
    override fun getInputFilter(): FileBasedIndex.InputFilter = DefaultFileTypeSpecificInputFilter(DamlFileType)
    override fun getKeyDescriptor(): KeyDescriptor<String> = EnumeratorStringDescriptor.INSTANCE
    override fun getIndexer(): DataIndexer<String, Void, FileContent> = DataIndexer { input ->
        if (!isSource(input.file.path)) emptyMap() else {
            val model = DamlSourceModel.parse(input.contentAsText.toString())
            buildMap {
                model.symbols.filter { it.kind == DamlSourceModel.Kind.INSTANCE }.forEach { put(IMPLEMENTATION_PREFIX + it.name, null) }
                model.module?.let { put(MODULE_PREFIX + it, null) }
                model.symbols.filter { it.kind !in DamlSourceModel.PRIVATE_KINDS }.forEach { put(SYMBOL_PREFIX + it.name, null) }
            }
        }
    }
    companion object {
        val NAME: ID<String, Void> = ID.create("daml.symbols")
        const val MODULE_PREFIX = "module:"
        const val IMPLEMENTATION_PREFIX = "implementation:"
        const val SYMBOL_PREFIX = "symbol:"
        val IGNORED_DIRECTORIES = setOf(".daml", "build", "out", "node_modules", ".gradle", ".git")
        fun isSource(path: String) = path.split('/').none { it in IGNORED_DIRECTORIES }
    }
}
