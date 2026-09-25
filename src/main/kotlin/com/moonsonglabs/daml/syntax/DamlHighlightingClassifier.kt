package com.moonsonglabs.daml.syntax

import com.moonsonglabs.daml.DamlKeywords
import com.moonsonglabs.daml.lang.DamlSourceModel
import com.moonsonglabs.daml.lang.DamlSourceModel.Kind

object DamlHighlightingClassifier {
    enum class Role {
        MODULE_NAME, DECLARATION_NAME, CHOICE_NAME, FIELD_NAME, TYPE_REFERENCE,
        PRELUDE_TYPE_REFERENCE, TYPE_PARAMETER, IMPORT_SYMBOL, SCRIPT_DECLARATION,
        ABSTRACT_METHOD, BUILTIN, PARTY_NAME, THIS_REFERENCE, PREDEFINED_VALUE, LOCAL_NAME,
        FUNCTION_CALL
    }
    // Compatibility entry point for pure callers. Equality includes the full source, never just its hash.
    private val lastAnalysis = ThreadLocal<Pair<String, Map<Int, Role>>>()
    fun roleAt(fileText: String, tokenStart: Int, tokenText: String): Role? {
        if (tokenText.isEmpty()) return null
        val previous = lastAnalysis.get()
        val roles = if (previous?.first == fileText) previous.second else classify(DamlSourceModel.parse(fileText)).also {
            lastAnalysis.set(fileText to it)
        }
        return roles[tokenStart]
    }

    fun classify(
        model: DamlSourceModel,
        importedFunction: (DamlSourceModel.Token) -> Boolean = { false }
    ): Map<Int, Role> = buildMap {
        val functions = model.symbols.filter { isFunction(it, model) }.map { it.start }.toSet()
        val importsByLine = mutableMapOf<Int, DamlSourceModel.Import>()
        model.imports.forEach { import ->
            val first = model.tokenAt(import.start)?.line ?: 0
            val last = model.tokenAt(import.end - 1)?.line ?: first
            for (line in first..last) importsByLine[line] = import
        }
        val recordStack = ArrayDeque<Int>()
        model.lines.forEach { line ->
            val ts = line.tokens
            if (ts.isEmpty()) return@forEach
            while (recordStack.isNotEmpty() && line.indent <= recordStack.last()) recordStack.removeLast()
            val recordFields = recordStack.isNotEmpty()
            if (ts.last().text == "with") recordStack.addLast(line.indent)
            val colon = ts.indexOfFirst { it.text == ":" }
            val eq = ts.indexOfFirst { it.text == "=" }
            val import = importsByLine[ts.first().line]
            ts.forEachIndexed { index, token ->
                val name = token.text
                if (!DamlSourceModel.isName(name)) return@forEachIndexed
                val declaration = model.declaration(token.start)
                val binding = declaration ?: model.local(name, token.start)
                val next = ts.getOrNull(index + 1)?.text
                val prev = ts.getOrNull(index - 1)?.text
                val role: Role? = when {
                    ts.first().text == "module" && index > 0 && name != "where" -> Role.MODULE_NAME
                    import != null -> if (token.start in import.moduleStart until import.moduleEnd || name == import.alias) Role.MODULE_NAME else if (name in import.names.orEmpty()) Role.IMPORT_SYMBOL else null
                    next == "." && name.first().isUpperCase() -> Role.MODULE_NAME
                    declaration != null && recordFields && index == 0 && next == "=" -> Role.FIELD_NAME
                    declaration != null -> declarationRole(declaration)
                    name == "this" -> Role.THIS_REFERENCE
                    name == "self" -> Role.PREDEFINED_VALUE
                    colon >= 0 && index > colon && (eq < 0 || index < eq) -> when {
                        name in DamlKeywords.preludeTypes -> Role.PRELUDE_TYPE_REFERENCE
                        name.first().isUpperCase() -> Role.TYPE_REFERENCE
                        name !in DamlKeywords.all -> Role.TYPE_PARAMETER
                        else -> null
                    }
                    prev == "." && ts.getOrNull(index - 2)?.text?.firstOrNull()?.isUpperCase() == true -> when {
                        name.first().isUpperCase() -> Role.TYPE_REFERENCE
                        importedFunction(token) -> Role.FUNCTION_CALL
                        else -> null
                    }
                    prev == "." -> Role.FIELD_NAME
                    recordFields && index == 0 && (ts.size == 1 || next == "=") -> Role.FIELD_NAME
                    next == "=" && ts.take(index).any { it.text == "with" } -> Role.FIELD_NAME
                    binding?.party == true -> Role.PARTY_NAME
                    binding?.kind == Kind.METHOD -> Role.ABSTRACT_METHOD
                    binding?.start in functions -> Role.FUNCTION_CALL
                    binding != null && name in DamlKeywords.builtins + DamlKeywords.contractClauseKeywords -> Role.LOCAL_NAME
                    name.first().isUpperCase() && (prev == "@" || prev == ".") -> Role.TYPE_REFERENCE
                    ts.first().text == "interface" && ts.getOrNull(1)?.text == "instance" && name.first().isUpperCase() -> Role.DECLARATION_NAME
                    name in DamlKeywords.builtins && binding == null -> Role.BUILTIN
                    binding == null && DamlSourceModel.isLowerName(name) && name !in DamlKeywords.all &&
                        importedFunction(token) -> Role.FUNCTION_CALL
                    else -> null
                }
                if (role != null) put(token.start, role)
            }
        }
    }

    internal fun isFunction(symbol: DamlSourceModel.Symbol, model: DamlSourceModel): Boolean {
        if (symbol.kind !in FUNCTION_KINDS) return false
        return (listOf(symbol.start) + symbol.aliases).any { offset ->
            val tokens = model.lineAt(offset).tokens.dropWhile { it.start <= offset }
            val separator = tokens.indexOfFirst { it.text == ":" || it.text == "=" || it.text == "<-" }
            when (tokens.getOrNull(separator)?.text) {
                "=" -> separator > 0 // A definition with explicit arguments, including local helpers.
                ":" -> hasFunctionType(tokens.drop(separator + 1).map { it.text })
                else -> false
            }
        }
    }

    private fun hasFunctionType(tokens: List<String>): Boolean {
        var depth = 0
        tokens.forEach { token ->
            when (token) {
                "(", "[", "{" -> depth++
                ")", "]", "}" -> depth--
                "->" -> if (depth == 0) return true
            }
        }
        return false
    }

    private val FUNCTION_KINDS = setOf(Kind.FUNCTION, Kind.VALUE, Kind.LOCAL)

    private fun declarationRole(symbol: DamlSourceModel.Symbol): Role? = when {
        symbol.party -> Role.PARTY_NAME
        symbol.kind == Kind.CHOICE -> Role.CHOICE_NAME
        symbol.kind == Kind.METHOD -> Role.ABSTRACT_METHOD
        symbol.kind == Kind.FIELD -> Role.FIELD_NAME
        symbol.kind == Kind.TYPE_PARAMETER -> Role.TYPE_PARAMETER
        symbol.kind == Kind.FUNCTION && symbol.signature?.startsWith("Script") == true -> Role.SCRIPT_DECLARATION
        symbol.kind in setOf(Kind.PARAMETER, Kind.LOCAL) -> if (symbol.name in DamlKeywords.builtins) Role.LOCAL_NAME else null
        else -> Role.DECLARATION_NAME
    }
}
