package com.moonsonglabs.daml.lang

import com.intellij.openapi.progress.ProgressManager
import com.intellij.psi.PsiFile
import com.intellij.psi.util.CachedValueProvider
import com.intellij.psi.util.CachedValuesManager
import com.moonsonglabs.daml.DamlKeywords
import com.moonsonglabs.daml.DamlLexer
import com.moonsonglabs.daml.DamlTokenTypes

/** A tolerant, lexical model. It deliberately does not infer types or guess ambiguous field receivers. */
class DamlSourceModel private constructor(val text: String) {
    enum class Kind { TEMPLATE, INTERFACE, DATA, NEWTYPE, TYPE, CLASS, EXCEPTION, CHOICE, FUNCTION, VALUE, FIELD, METHOD, PARAMETER, LOCAL, CONSTRUCTOR, TYPE_PARAMETER, INSTANCE }
    data class Token(val text: String, val start: Int, val end: Int, val line: Int, val column: Int, val code: Boolean)
    data class Symbol(
        val name: String, val start: Int, val kind: Kind, val header: Int,
        val end: Int, val scopeStart: Int, val scopeEnd: Int, val owner: Int?,
        val signature: String? = null, val party: Boolean = false,
        val aliases: MutableList<Int> = mutableListOf()
    ) { fun visibleAt(offset: Int) = offset in scopeStart until scopeEnd }
    data class Import(val module: String, val start: Int, val end: Int, val moduleStart: Int, val moduleEnd: Int,
        val qualified: Boolean, val alias: String?, val names: Set<String>?, val hiding: Boolean,
        val members: Set<String> = emptySet()) {
        fun exposes(name: String, owner: String? = null): Boolean {
            val listed = names?.contains(name) == true || owner in members
            return if (names == null) true else if (hiding) !listed else listed
        }
        fun matches(qualifier: String) = qualifier == (alias ?: module)
    }
    data class Line(val start: Int, val end: Int, val indent: Int, val tokens: List<Token>)
    val tokens: List<Token>
    val lines: List<Line>
    val imports: List<Import>
    val module: String?
    val moduleStart: Int?
    val exports: Set<String>?
    private val exportMembers: Set<String>
    val symbols: List<Symbol>
    val byName: Map<String, List<Symbol>>
    val declarationOffsets: Map<Int, Symbol>
    private val tokenOffsets: Map<Int, Token>
    private val lineEnds: IntArray
    private val strictLineEnds: IntArray
    private val symbolsByOffset: Map<Int, Symbol>

    init {
        val lexer = DamlLexer()
        lexer.start(text)
        val collected = mutableListOf<Token>()
        var line = 0
        var lineStart = 0
        while (lexer.tokenType != null) {
            ProgressManager.checkCanceled()
            val start = lexer.tokenStart
            val end = lexer.tokenEnd
            val value = text.substring(start, end)
            val type = lexer.tokenType
            if (type != DamlTokenTypes.WHITE_SPACE) {
                collected += Token(value, start, end, line, start - lineStart,
                    !DamlTokenTypes.COMMENTS.contains(type) && !DamlTokenTypes.STRINGS.contains(type) && type != DamlTokenTypes.PRAGMA)
            }
            for (offset in start until end) if (text[offset] == '\n') { line++; lineStart = offset + 1 }
            lexer.advance()
        }
        tokens = collected
        tokenOffsets = tokens.associateBy { it.start }
        val grouped = tokens.filter { it.code }.groupBy { it.line }
        var offset = 0
        lines = text.split('\n').mapIndexed { index, value ->
            val start = offset
            offset += value.length + 1
            Line(start, start + value.length, value.takeWhile { it == ' ' || it == '\t' }.length, grouped[index].orEmpty())
        }
        // Nearest next layout boundary in linear time, including incomplete declarations.
        lineEnds = IntArray(lines.size) { text.length }
        val stack = ArrayDeque<Int>()
        lines.forEachIndexed { i, current ->
            if (current.tokens.isNotEmpty()) {
                while (stack.isNotEmpty() && lines[stack.last()].indent >= current.indent) lineEnds[stack.removeLast()] = current.start
                stack.addLast(i)
            }
        }
        strictLineEnds = IntArray(lines.size) { text.length }
        stack.clear()
        lines.forEachIndexed { i, current ->
            if (current.tokens.isNotEmpty()) {
                while (stack.isNotEmpty() && lines[stack.last()].indent > current.indent) strictLineEnds[stack.removeLast()] = current.start
                stack.addLast(i)
            }
        }
        val code = tokens.filter { it.code }
        val moduleIndex = code.indexOfFirst { it.text == "module" }
        val moduleTokens = if (moduleIndex >= 0) code.drop(moduleIndex + 1).takeWhile { it.text != "where" } else emptyList()
        val moduleNameTokens = moduleTokens.takeWhile { it.text != "(" && it.text != "()" }
        module = moduleNameTokens.joinToString("") { it.text }.ifEmpty { null }
        moduleStart = moduleNameTokens.firstOrNull()?.start
        exports = if (moduleTokens.any { it.text == "(" || it.text == "()" }) moduleTokens.drop(moduleNameTokens.size).filter { isName(it.text) }.map { it.text }.toSet() else null
        exportMembers = moduleTokens.windowed(3).filter { it[1].text == "(" && it[2].text == ".." }.map { it[0].text }.toSet()
        imports = parseImports(code)
        symbols = parseSymbols()
        symbolsByOffset = symbols.associateBy { it.start }
        byName = symbols.groupBy { it.name }
        declarationOffsets = buildMap { symbols.filter { it.kind != Kind.INSTANCE }.forEach { symbol -> put(symbol.start, symbol); symbol.aliases.forEach { put(it, symbol) } } }
    }

    fun tokenAt(offset: Int): Token? {
        tokenOffsets[offset]?.let { return it }
        val index = tokens.binarySearch { it.start.compareTo(offset) }
        return tokens.getOrNull(if (index >= 0) index else -index - 2)?.takeIf { offset < it.end }
    }
    fun local(name: String, offset: Int): Symbol? {
        val candidates = byName[name].orEmpty().filter { it.visibleAt(offset) && it.kind != Kind.INSTANCE }
        val narrowest = candidates.minOfOrNull { it.scopeEnd - it.scopeStart } ?: return null
        return candidates.filter { it.scopeEnd - it.scopeStart == narrowest }.maxByOrNull { it.start }
    }
    fun declaration(offset: Int) = declarationOffsets[offset]
    fun lineAt(offset: Int): Line = lines[(tokenAt(offset)?.line ?: text.take(offset).count { it == '\n' }).coerceIn(lines.indices)]
    fun owner(symbol: Symbol) = symbol.owner?.let(symbolsByOffset::get)
    fun exported(symbol: Symbol): Boolean = symbol.kind !in PRIVATE_KINDS &&
        owner(symbol)?.kind !in setOf(Kind.FUNCTION, Kind.VALUE, Kind.METHOD, Kind.LOCAL, Kind.INSTANCE) &&
        (exports == null || symbol.name in exports || owner(symbol)?.name in exportMembers)

    private fun parseImports(code: List<Token>): List<Import> = buildList {
        var i = 0
        while (i < code.size) {
            val first = code[i++]
            if (first.text != "import") continue
            val declaration = mutableListOf<Token>()
            var depth = 0
            while (i < code.size) {
                val next = code[i]
                if (next.line > first.line && next.column <= first.column && depth == 0) break
                if (next.text == "(") depth++
                if (next.text == ")") depth--
                declaration += next
                i++
            }
            var cursor = 0
            val qualified = declaration.getOrNull(cursor)?.text == "qualified"
            if (qualified) cursor++
            val nameTokens = mutableListOf<Token>()
            while (cursor < declaration.size && (declaration[cursor].text.firstOrNull()?.isUpperCase() == true || declaration[cursor].text == ".")) nameTokens += declaration[cursor++]
            if (nameTokens.isEmpty()) continue
            val alias = if (declaration.getOrNull(cursor)?.text == "as") declaration.getOrNull(++cursor)?.text.also { cursor++ } else null
            val hiding = declaration.getOrNull(cursor)?.text == "hiding"
            if (hiding) cursor++
            val list = declaration.drop(cursor)
            val names = if (list.firstOrNull()?.text in setOf("(", "()")) list.filter { isName(it.text) }.map { it.text }.toSet() else null
            val members = list.windowed(3).filter { it[1].text == "(" && it[2].text == ".." }.map { it[0].text }.toSet()
            add(Import(nameTokens.joinToString("") { it.text }, first.start, declaration.lastOrNull()?.end ?: first.end,
                nameTokens.first().start, nameTokens.last().end, qualified, alias, names, hiding, members))
        }
    }

    private fun parseSymbols(): List<Symbol> {
        val result = mutableListOf<Symbol>()
        val owners = ArrayDeque<Pair<Int, Symbol>>()
        val canonical = mutableMapOf<Pair<Int?, String>, Symbol>()
        val symbolIndexes = mutableMapOf<Int, Int>()
        fun add(token: Token, kind: Kind, lineIndex: Int, owner: Symbol?, signature: String? = null,
                scopeStart: Int? = null, scopeEnd: Int? = null, party: Boolean = false): Symbol {
            val current = lines[lineIndex]
            val key = owner?.start to token.text
            if (kind in setOf(Kind.FUNCTION, Kind.VALUE, Kind.METHOD)) {
                canonical[key]?.takeIf { it.kind in setOf(Kind.FUNCTION, Kind.VALUE, Kind.METHOD) }?.let {
                    val merged = it.copy(end = maxOf(it.end, lineEnds[lineIndex]))
                    merged.aliases += token.start
                    result[symbolIndexes.getValue(it.start)] = merged
                    canonical[key] = merged
                    return merged
                }
            }
            val symbol = Symbol(token.text, token.start, kind, current.start, lineEnds[lineIndex],
                scopeStart ?: owner?.header ?: 0, scopeEnd ?: owner?.end ?: text.length, owner?.start,
                signature, party)
            symbolIndexes[symbol.start] = result.size
            result += symbol
            if (kind !in setOf(Kind.PARAMETER, Kind.LOCAL, Kind.TYPE_PARAMETER)) canonical[key] = symbol
            return symbol
        }
        lines.forEachIndexed { index, current ->
            ProgressManager.checkCanceled()
            val ts = current.tokens
            if (ts.isEmpty() || imports.any { ts.first().start in it.start until it.end } || ts.first().text == "module") return@forEachIndexed
            while (owners.isNotEmpty() && current.indent <= owners.last().first) owners.removeLast()
            val owner = owners.lastOrNull()?.second
            val words = ts.map { it.text }
            val first = words.first()
            val typeKind = TYPE_KINDS[first]
            val choiceIndex = words.indexOf("choice").takeIf { it in 0..1 }
                ?: words.indexOf("can").takeIf { first == "controller" && it >= 0 }
            val instance = first == "interface" && words.getOrNull(1) == "instance"
            if (typeKind != null || choiceIndex != null) {
                val name = ts.getOrNull(if (instance) 2 else if (choiceIndex != null) choiceIndex + 1 else 1)
                    ?.takeIf { isName(it.text) } ?: return@forEachIndexed
                val kind = if (instance) Kind.INSTANCE else if (choiceIndex != null) Kind.CHOICE else typeKind!!
                val symbol = add(name, kind, index, owner, signature = text.substring(name.end, current.end).trim())
                owners.addLast(current.indent to symbol)
                if (kind in setOf(Kind.DATA, Kind.NEWTYPE, Kind.TYPE, Kind.CLASS)) {
                    ts.dropWhile { it.start <= name.start }.takeWhile { it.text !in setOf("=", "with", "where", ":") }
                        .filter { isLowerName(it.text) }.forEach { add(it, Kind.TYPE_PARAMETER, index, symbol) }
                    ts.forEachIndexed { at, token -> if (token.text.firstOrNull()?.isUpperCase() == true && ts.getOrNull(at - 1)?.text in setOf("=", "|")) add(token, Kind.CONSTRUCTOR, index, symbol, scopeStart = 0, scopeEnd = text.length) }
                }
                return@forEachIndexed
            }
            if (first == "|" && owner?.kind in setOf(Kind.DATA, Kind.NEWTYPE)) {
                ts.getOrNull(1)?.let { add(it, Kind.CONSTRUCTOR, index, owner, scopeStart = 0, scopeEnd = text.length) }
            }
            val startIndex = if (first in setOf("let", "with")) 1 else 0
            val name = ts.getOrNull(startIndex) ?: return@forEachIndexed
            val separator = ts.indexOfFirst { it.text in setOf(":", "=", "<-") }
            if (isLowerName(name.text) && separator > startIndex && name.text !in RESERVED) {
                val sep = ts[separator]
                val structuralOwner = owner?.kind in setOf(Kind.TEMPLATE, Kind.DATA, Kind.NEWTYPE, Kind.CHOICE, Kind.EXCEPTION)
                val member = owner?.kind in setOf(Kind.INTERFACE, Kind.CLASS, Kind.INSTANCE)
                val kind = when {
                    sep.text == "<-" || first == "let" -> Kind.LOCAL
                    structuralOwner && separator == startIndex + 1 -> Kind.FIELD
                    member -> if (name.text == "view") Kind.FIELD else Kind.METHOD
                    separator > startIndex + 1 || sep.text == ":" -> Kind.FUNCTION
                    current.indent == 0 -> Kind.VALUE
                    else -> Kind.LOCAL
                }
                val signature = if (sep.text == ":") text.substring(sep.end, current.end).trim() else null
                val parentEnd = owner?.end ?: text.length
                val scopeEnd = if (kind == Kind.LOCAL) minOf(parentEnd, nextDedent(index)) else null
                val symbol = add(name, kind, index, owner, signature,
                    if (kind == Kind.LOCAL) (if (sep.text == "<-") lineEnds[index] else name.end) else null, scopeEnd,
                    signature?.matches(PARTY_TYPE) == true || ts.getOrNull(separator + 1)?.text?.startsWith("allocateParty") == true)
                if (sep.text == "=" && kind in setOf(Kind.FUNCTION, Kind.VALUE, Kind.METHOD)) {
                    ts.subList(startIndex + 1, separator).filter { isLowerName(it.text) && it.text !in RESERVED }
                        .forEach { add(it, Kind.PARAMETER, index, symbol, scopeStart = sep.end, scopeEnd = lineEnds[index]) }
                    // The definition owns its body even when its signature is the canonical symbol.
                    owners.addLast(current.indent to symbol.copy(header = current.start, end = lineEnds[index]))
                }
            }
            // Lambda and case-pattern variables live only in their arm; no cross-arm leakage.
            val arrow = ts.indexOfFirst { it.text == "->" }
            if (arrow >= 0 && words.take(arrow).none { it == ":" } && ("\\" in words.take(arrow) || "=" !in words.take(arrow))) {
                val from = words.indexOf("\\").let { if (it >= 0) it + 1 else 0 }
                var scopeEnd = lineEnds[index]
                if (from > 0) {
                    var depth = ts.take(from - 1).sumOf { if (it.text == "(") 1 else if (it.text == ")") -1 else 0 }
                    val enclosingDepth = depth
                    if (enclosingDepth > 0) for (at in arrow + 1 until ts.size) {
                        if (ts[at].text == "(") depth++
                        if (ts[at].text == ")") depth--
                        if (depth < enclosingDepth) { scopeEnd = ts[at].start; break }
                    }
                }
                ts.subList(from, arrow).filter { isLowerName(it.text) && it.text !in RESERVED }.forEach {
                    add(it, Kind.PARAMETER, index, owner, scopeStart = ts[arrow].end, scopeEnd = scopeEnd)
                }
            }
        }
        return result
    }
    private fun nextDedent(index: Int): Int = strictLineEnds[index]
    companion object {
        val PRIVATE_KINDS = setOf(Kind.PARAMETER, Kind.LOCAL, Kind.TYPE_PARAMETER, Kind.INSTANCE)
        private val RESERVED = DamlKeywords.haskellKeywords + setOf("signatory", "observer", "controller", "ensure", "maintainer", "choice", "with", "viewtype")
        private val PARTY_TYPE = Regex("(?:Optional\\s+|List\\s+)?Party")
        private val TYPE_KINDS = mapOf("template" to Kind.TEMPLATE, "interface" to Kind.INTERFACE, "data" to Kind.DATA,
            "newtype" to Kind.NEWTYPE, "type" to Kind.TYPE, "class" to Kind.CLASS, "exception" to Kind.EXCEPTION)
        fun isName(value: String) = value.isNotEmpty() && (value[0].isLetter() || value[0] == '_') && value.all { it.isLetterOrDigit() || it == '_' || it == '\'' }
        fun isLowerName(value: String) = isName(value) && (value[0].isLowerCase() || value[0] == '_')
        fun parse(text: String) = DamlSourceModel(text)
        fun get(file: PsiFile): DamlSourceModel = CachedValuesManager.getCachedValue(file) {
            CachedValueProvider.Result.create(parse(file.text), file)
        }
    }
}
