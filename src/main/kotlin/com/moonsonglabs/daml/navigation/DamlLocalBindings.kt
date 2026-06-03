package com.moonsonglabs.daml.navigation

object DamlLocalBindings {
    data class Binding(val name: String, val startOffset: Int, val endOffset: Int)

    private val localBindingRegex = Regex("""^(\s*)([a-z_][A-Za-z0-9_']*)\s*(?::|<-|=(?!=))""")
    private val ownerRegex = Regex(
        """^\s*(?:interface|template|data|newtype|exception|(?:(?:nonconsuming|preconsuming|postconsuming)\s+)?choice)\b"""
    )

    fun resolve(text: String, reference: DamlModuleNames.SymbolReference): Binding? {
        if (reference.qualifier != null) return null
        if (reference.name.firstOrNull()?.isLowerCase() != true && reference.name.firstOrNull() != '_') return null

        return bindings(text)
            .asSequence()
            .filter { it.name == reference.name }
            .filter { it.startOffset < reference.startOffset }
            .filter { it.startOffset != reference.startOffset }
            .filter { inScope(text, it, reference.startOffset) }
            .maxByOrNull { it.startOffset }
    }

    private fun bindings(text: String): List<Binding> {
        val lines = lineInfos(text)
        return lines.mapNotNull { line ->
            val match = localBindingRegex.find(line.text) ?: return@mapNotNull null
            val group = match.groups[2] ?: return@mapNotNull null
            Binding(group.value, line.startOffset + group.range.first, line.startOffset + group.range.last + 1)
        }
    }

    private fun inScope(text: String, binding: Binding, referenceOffset: Int): Boolean {
        val lines = lineInfos(text)
        val bindingLineIndex = lines.indexOfLast { binding.startOffset in it.startOffset..it.endOffset }
        if (bindingLineIndex < 0) return false

        val ownerIndent = ownerIndent(lines, bindingLineIndex)
        val bindingIndent = lines[bindingLineIndex].indent
        val scopedToOwner = ownerIndent < bindingIndent
        for (index in bindingLineIndex + 1 until lines.size) {
            val line = lines[index]
            if (line.startOffset >= referenceOffset) return true
            val trimmed = line.text.trim()
            if (trimmed.isEmpty() || trimmed.startsWith("--")) continue
            if (scopedToOwner && line.indent <= ownerIndent) return false
            if (line.indent <= ownerIndent && ownerRegex.containsMatchIn(line.text)) return false
        }
        return true
    }

    private fun ownerIndent(lines: List<LineInfo>, bindingLineIndex: Int): Int {
        val bindingIndent = lines[bindingLineIndex].indent
        for (index in bindingLineIndex - 1 downTo 0) {
            val line = lines[index]
            val trimmed = line.text.trim()
            if (trimmed.isEmpty() || trimmed.startsWith("--")) continue
            if (line.indent < bindingIndent && ownerRegex.containsMatchIn(line.text)) return line.indent
        }
        return bindingIndent
    }

    private fun lineInfos(text: String): List<LineInfo> {
        val lines = mutableListOf<LineInfo>()
        var start = 0
        while (start <= text.length) {
            val end = text.indexOf('\n', start).let { if (it == -1) text.length else it }
            val lineText = text.substring(start, end)
            lines += LineInfo(start, end, lineText, lineText.takeWhile { it == ' ' || it == '\t' }.length)
            if (end == text.length) break
            start = end + 1
        }
        return lines
    }

    private data class LineInfo(
        val startOffset: Int,
        val endOffset: Int,
        val text: String,
        val indent: Int
    )
}
