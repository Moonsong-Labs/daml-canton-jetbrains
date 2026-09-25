package com.moonsonglabs.daml.navigation

import com.moonsonglabs.daml.lang.DamlSourceModel

object DamlModuleNames {
    private val identifierRegex = Regex("""[A-Za-z_][A-Za-z0-9_']*""")

    data class ModuleDeclaration(val name: String, val startOffset: Int)
    data class SymbolDeclaration(val name: String, val startOffset: Int)
    data class ImportDeclaration(
        val moduleName: String,
        val qualified: Boolean,
        val alias: String?,
        val symbols: Set<String>,
        val hiding: Boolean,
        val explicitList: Boolean = symbols.isNotEmpty()
    ) {
        fun exposes(symbolName: String): Boolean =
            if (!explicitList) true else if (hiding) symbolName !in symbols else symbolName in symbols

        fun qualifierMatches(qualifier: String): Boolean =
            (alias ?: moduleName) == qualifier
    }
    data class SymbolReference(
        val name: String,
        val startOffset: Int,
        val endOffset: Int,
        val qualifier: String? = null,
        val qualifierStartOffset: Int = -1,
        val qualifierEndOffset: Int = -1
    )
    data class ImportReference(
        val moduleName: String,
        val startOffset: Int,
        val endOffset: Int,
        val symbolName: String? = null,
        val symbolStartOffset: Int = -1,
        val symbolEndOffset: Int = -1
    ) {
        fun isSymbolReference(): Boolean = symbolName != null
    }

    fun declaredModule(text: String): ModuleDeclaration? {
        val model = DamlSourceModel.parse(text)
        return model.module?.let { ModuleDeclaration(it, model.moduleStart ?: 0) }
    }

    fun imports(text: String): List<ImportDeclaration> = DamlSourceModel.parse(text).imports.map {
        ImportDeclaration(it.module, it.qualified, it.alias, it.names.orEmpty(), it.hiding, it.names != null)
    }

    fun importAt(text: String, offset: Int): ImportReference? {
        val model = DamlSourceModel.parse(text)
        val declaration = model.imports.firstOrNull { offset in it.start until it.end } ?: return null
        if (offset in declaration.moduleStart..declaration.moduleEnd) return ImportReference(declaration.module, declaration.moduleStart, declaration.moduleEnd)
        val token = model.tokenAt(offset)?.takeIf { it.text in declaration.names.orEmpty() } ?: return null
        return ImportReference(declaration.module, declaration.moduleStart, declaration.moduleEnd, token.text, token.start, token.end)
    }

    fun declarations(text: String): List<SymbolDeclaration> = DamlSourceModel.parse(text).symbols.map { SymbolDeclaration(it.name, it.start) }

    fun declarationNamed(text: String, symbolName: String): SymbolDeclaration? =
        declarations(text).firstOrNull { it.name == symbolName.trim('`') }

    fun navigableDeclarationNamed(text: String, symbolName: String): SymbolDeclaration? =
        navigableDeclarations(text).firstOrNull { it.name == symbolName.trim('`') }

    fun declarationAt(text: String, offset: Int): SymbolDeclaration? {
        val token = identifierAt(text, offset) ?: return null
        return declarations(text).firstOrNull { declaration ->
            declaration.name == token.name && declaration.startOffset == token.startOffset
        }
    }

    fun symbolAt(text: String, offset: Int): SymbolReference? {
        if (text.isEmpty()) return null
        val token = identifierAt(text, offset) ?: return null
        if (!isCodePosition(text, token.startOffset)) return null
        if (token.endOffset < text.length && text[token.endOffset] == '.') return null

        val qualifier = qualifierBefore(text, token.startOffset)
        return token.copy(
            qualifier = qualifier?.name,
            qualifierStartOffset = qualifier?.startOffset ?: -1,
            qualifierEndOffset = qualifier?.endOffset ?: -1
        )
    }

    fun symbolAtOrNear(text: String, offset: Int): SymbolReference? {
        symbolAt(text, offset)?.let { return it }
        if (offset > 0) symbolAt(text, offset - 1)?.let { return it }
        return symbolAfterTypeApplicationMarker(text, offset)
    }

    fun symbolAfterTypeApplicationMarker(text: String, offset: Int): SymbolReference? {
        if (text.isEmpty()) return null
        val markerOffset = when {
            offset in text.indices && text[offset] == '@' -> offset
            offset > 0 && offset - 1 in text.indices && text[offset - 1] == '@' -> offset - 1
            else -> return null
        }
        var cursor = markerOffset + 1
        while (cursor < text.length && text[cursor].isWhitespace()) cursor++
        if (cursor >= text.length || !isIdentifierPart(text[cursor])) return null
        return symbolAt(text, cursor)
    }

    fun isCodePosition(text: String, offset: Int): Boolean = DamlSourceModel.parse(text).tokenAt(offset)?.code == true

    private fun navigableDeclarations(text: String): List<SymbolDeclaration> =
        DamlSourceModel.parse(text).let { model -> model.symbols.filter { model.exported(it) }.map { SymbolDeclaration(it.name, it.start) } }

    private fun identifierAt(text: String, offset: Int): SymbolReference? {
        val clamped = offset.coerceIn(0, text.lastIndex.coerceAtLeast(0))
        if (!isIdentifierPart(text[clamped])) return null

        var start = clamped
        while (start > 0 && isIdentifierPart(text[start - 1])) start--
        var end = clamped + 1
        while (end < text.length && isIdentifierPart(text[end])) end++

        val name = text.substring(start, end)
        if (!identifierRegex.matches(name)) return null
        return SymbolReference(name, start, end)
    }

    private fun qualifierBefore(text: String, tokenStart: Int): SymbolReference? {
        val dot = tokenStart - 1
        if (dot < 1 || text[dot] != '.') return null

        var start = dot - 1
        while (start >= 0 && (isIdentifierPart(text[start]) || text[start] == '.')) start--
        start++
        if (start >= dot) return null

        val qualifier = text.substring(start, dot)
        if (!qualifier.split('.').all { identifierRegex.matches(it) }) return null
        return SymbolReference(qualifier, start, dot)
    }

    private fun isIdentifierPart(c: Char): Boolean =
        c.isLetterOrDigit() || c == '_' || c == '\''

}
