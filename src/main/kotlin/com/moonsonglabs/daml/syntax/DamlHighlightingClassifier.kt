package com.moonsonglabs.daml.syntax

import com.moonsonglabs.daml.DamlKeywords

object DamlHighlightingClassifier {
    enum class Role {
        MODULE_NAME,
        DECLARATION_NAME,
        CHOICE_NAME,
        FIELD_NAME,
        TYPE_REFERENCE,
        PRELUDE_TYPE_REFERENCE,
        TYPE_PARAMETER,
        IMPORT_SYMBOL,
        SCRIPT_DECLARATION,
        ABSTRACT_METHOD,
        BUILTIN,
        PARTY_NAME,
        THIS_REFERENCE,
        PREDEFINED_VALUE
    }

    private val moduleDeclarationRegex = Regex(
        """^\s*module\s+([A-Z][A-Za-z0-9_']*(?:\.[A-Z][A-Za-z0-9_']*)*)\b"""
    )
    private val importDeclarationRegex = Regex(
        """^\s*import\s+(?:qualified\s+)?([A-Z][A-Za-z0-9_']*(?:\.[A-Z][A-Za-z0-9_']*)*)\b"""
    )
    private val declarationRegex = Regex(
        """^\s*(?:template|interface|data|newtype|type|class|exception)\s+([A-Z][A-Za-z0-9_']*)\b"""
    )
    private val interfaceInstanceRegex = Regex(
        """^\s*interface\s+instance\s+([A-Z][A-Za-z0-9_']*)\s+for\s+([A-Z][A-Za-z0-9_']*)\b"""
    )
    private val choiceRegex = Regex(
        """^\s*(?:(?:nonconsuming|preconsuming|postconsuming)\s+)?choice\s+([A-Z][A-Za-z0-9_']*)\b"""
    )
    private val controllerFirstChoiceRegex = Regex(
        """^\s*controller\b.*\bcan\s+([A-Z][A-Za-z0-9_']*)\b"""
    )
    private val dataTypeParameterRegex = Regex(
        """^\s*(?:data|newtype|type)\s+[A-Z][A-Za-z0-9_']*((?:\s+[a-z_][A-Za-z0-9_']*)+)"""
    )
    private val partyBindingRegex = Regex(
        """(?m)^\s*([a-z_][A-Za-z0-9_']*)\s*<-\s*(?:allocateParty|allocatePartyWithHint)\b"""
    )
    private val typedPartyBindingRegex = Regex(
        """(?m)^\s*([a-z_][A-Za-z0-9_']*)\s*:\s*(?:Optional\s+|List\s+)?Party\b"""
    )
    private val abstractMethodRegex = Regex(
        """^\s*([a-z_][A-Za-z0-9_']*)\s*:\s*.+"""
    )
    private val interfaceInstanceMethodRegex = Regex(
        """^\s*([a-z_][A-Za-z0-9_']*)\b.*="""
    )
    private val identifierRegex = Regex("""[A-Za-z_][A-Za-z0-9_']*""")
    private val analysisCache = object : LinkedHashMap<CacheKey, Analysis>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<CacheKey, Analysis>?): Boolean = size > 32
    }

    fun roleAt(fileText: String, tokenStart: Int, tokenText: String): Role? {
        if (tokenText.isEmpty() || tokenStart !in fileText.indices) return null
        if (!isIdentifier(tokenText)) return null
        val analysis = analysis(fileText)

        val tokenEnd = tokenStart + tokenText.length
        val lineStart = fileText.lastIndexOf('\n', tokenStart - 1).let { if (it == -1) 0 else it + 1 }
        val lineEnd = fileText.indexOf('\n', tokenStart).let { if (it == -1) fileText.length else it }
        val line = fileText.substring(lineStart, lineEnd)
        val lineOffset = tokenStart - lineStart
        val tokenEndInLine = tokenEnd - lineStart
        val after = line.substring(tokenEndInLine).trimStart()

        if (isTypeIdentifier(tokenText) && after.startsWith(".")) return Role.MODULE_NAME

        moduleDeclarationRegex.find(line)?.let { match ->
            if (match.groupContains(1, lineOffset, tokenEndInLine)) return Role.MODULE_NAME
        }

        importDeclarationRegex.find(line)?.let { match ->
            if (isInsideImportList(line, lineOffset)) return Role.IMPORT_SYMBOL
            if (match.groupContains(1, lineOffset, tokenEndInLine)) return Role.MODULE_NAME
        }

        choiceRegex.find(line)?.let { match ->
            if (match.groupContains(1, lineOffset, tokenEndInLine)) return Role.CHOICE_NAME
        }
        controllerFirstChoiceRegex.find(line)?.let { match ->
            if (match.groupContains(1, lineOffset, tokenEndInLine)) return Role.CHOICE_NAME
        }

        declarationRegex.find(line)?.let { match ->
            if (match.groupContains(1, lineOffset, tokenEndInLine)) return Role.DECLARATION_NAME
        }
        interfaceInstanceRegex.find(line)?.let { match ->
            if (match.groupContains(1, lineOffset, tokenEndInLine) || match.groupContains(2, lineOffset, tokenEndInLine)) {
                return Role.DECLARATION_NAME
            }
        }
        interfaceInstanceMethodRegex.find(line)?.let { match ->
            val methodName = match.groups[1]?.value
            if (methodName != "view" &&
                match.groupContains(1, lineOffset, tokenEndInLine) &&
                isDirectInterfaceInstanceMember(fileText, lineStart, leadingIndent(line))
            ) {
                return Role.ABSTRACT_METHOD
            }
        }
        abstractMethodRegex.find(line)?.let { match ->
            if (match.groupContains(1, lineOffset, tokenEndInLine) &&
                isDirectInterfaceOrClassMember(fileText, lineStart, leadingIndent(line))
            ) {
                return Role.ABSTRACT_METHOD
            }
        }

        if (analysis.isAbstractMethodUse(tokenText, tokenStart, fileText, lineStart, leadingIndent(line))) {
            return Role.ABSTRACT_METHOD
        }

        if (isPartyDeclaration(line, lineOffset, tokenEndInLine) ||
            analysis.isPartyName(tokenText, tokenStart)
        ) {
            return Role.PARTY_NAME
        }

        if (tokenText == "this") return Role.THIS_REFERENCE
        if (tokenText == "self") return Role.PREDEFINED_VALUE

        val localDeclarationRole = localDeclarationRole(fileText, lineStart, line, lineOffset, tokenText)
        if (localDeclarationRole != null) return localDeclarationRole

        if (isTypeParameter(line, lineOffset, tokenText)) return Role.TYPE_PARAMETER

        if (tokenText in DamlKeywords.preludeTypes && isTypeReferenceContext(line, lineOffset, tokenText)) {
            return Role.PRELUDE_TYPE_REFERENCE
        }

        if (isTypeIdentifier(tokenText) && isTypeReferenceContext(line, lineOffset, tokenText)) {
            return Role.TYPE_REFERENCE
        }

        if (tokenText in DamlKeywords.builtins) return Role.BUILTIN

        return null
    }

    private fun localDeclarationRole(fileText: String, lineStart: Int, line: String, lineOffset: Int, tokenText: String): Role? {
        if (!isValueIdentifier(tokenText)) return null

        val before = line.substring(0, lineOffset)
        val after = line.substring(lineOffset + tokenText.length)
        val beforeTrimmed = before.trimEnd()
        val afterTrimmed = after.trimStart()
        val leadingIndent = line.indexOfFirst { !it.isWhitespace() }.let { if (it == -1) line.length else it }
        val topLevel = leadingIndent == 0
        val declarationStart = beforeTrimmed.isEmpty()
        val punnedFieldContext = declarationStart && isPunnedRecordFieldContext(fileText, lineStart, leadingIndent)
        val fieldStart = declarationStart ||
            beforeTrimmed.endsWith("with") ||
            beforeTrimmed.endsWith(";") ||
            beforeTrimmed.endsWith("{") ||
            beforeTrimmed.endsWith(",")

        if (declarationStart && afterTrimmed.startsWith(":")) {
            return if (afterTrimmed.removePrefix(":").trimStart().startsWith("Script")) {
                Role.SCRIPT_DECLARATION
            } else if (topLevel) {
                Role.DECLARATION_NAME
            } else {
                Role.FIELD_NAME
            }
        }

        if (declarationStart && afterTrimmed.startsWith("=")) {
            return if (afterTrimmed.removePrefix("=").trimStart().startsWith("script")) {
                Role.SCRIPT_DECLARATION
            } else if (topLevel) {
                Role.DECLARATION_NAME
            } else {
                Role.FIELD_NAME
            }
        }

        if (declarationStart && !topLevel && afterTrimmed.isEmpty() && punnedFieldContext) {
            return Role.FIELD_NAME
        }

        if (fieldStart && (
                afterTrimmed.startsWith(":") ||
                    afterTrimmed.startsWith("=") ||
                    afterTrimmed.startsWith(";") ||
                    afterTrimmed.isEmpty()
                )
        ) {
            if (declarationStart && afterTrimmed.isEmpty() && !punnedFieldContext) return null
            return Role.FIELD_NAME
        }

        if (beforeTrimmed.endsWith(".")) {
            return Role.FIELD_NAME
        }

        return null
    }

    private fun isPunnedRecordFieldContext(fileText: String, lineStart: Int, indent: Int): Boolean {
        var cursor = lineStart - 1
        while (cursor > 0) {
            val previousLineEnd = cursor
            val previousLineStart = fileText.lastIndexOf('\n', (previousLineEnd - 1).coerceAtLeast(0)).let {
                if (it == -1) 0 else it + 1
            }
            val previousLine = fileText.substring(previousLineStart, previousLineEnd).trimEnd()
            if (previousLine.isNotBlank()) {
                val previousIndent = leadingIndent(previousLine)
                val trimmed = previousLine.trim()
                if (previousIndent < indent) return trimmed.endsWith("with")
                if (previousIndent == indent &&
                    !(identifierRegex.matches(trimmed) ||
                        trimmed.contains("=") ||
                        trimmed.endsWith(",") ||
                        trimmed.endsWith(";"))
                ) {
                    return false
                }
            }
            cursor = previousLineStart - 1
        }
        return false
    }

    private fun isTypeParameter(line: String, lineOffset: Int, tokenText: String): Boolean {
        if (!isValueIdentifier(tokenText)) return false

        dataTypeParameterRegex.find(line)?.groups?.get(1)?.range?.let { range ->
            if (lineOffset in range) return true
        }

        val colon = line.indexOf(':')
        if (colon != -1 && lineOffset > colon) return true

        val forall = line.indexOf("forall")
        if (forall != -1 && lineOffset > forall) return true

        return false
    }

    private fun isTypeReferenceContext(line: String, lineOffset: Int, tokenText: String): Boolean {
        if (!isTypeIdentifier(tokenText)) return false

        val before = line.substring(0, lineOffset)
        val after = line.substring(lineOffset + tokenText.length)
        val beforeTrimmed = before.trimEnd()
        val afterTrimmed = after.trimStart()
        val previousNonSpace = before.lastOrNull { !it.isWhitespace() }

        if (previousNonSpace == '.' || previousNonSpace == '@') return true
        if (afterTrimmed.startsWith(".")) return true
        if (afterTrimmed.startsWith("with")) return true

        val colon = line.indexOf(':')
        if (colon != -1 && lineOffset > colon) return true

        val doubleColon = line.indexOf("::")
        if (doubleColon != -1 && lineOffset > doubleColon) return true

        val typeArrow = listOf("->", "→", "=>", "⇒").any { beforeTrimmed.endsWith(it) }
        if (typeArrow) return true

        if (Regex("""\b(?:createCmd|createExactCmd|exerciseCmd|exerciseExactCmd|create|exercise|fetch|query)\s+$""")
                .containsMatchIn(before)
        ) {
            return true
        }

        if (line.substring(0, lineOffset).contains(Regex("""\bderiving\s*\("""))) return true

        return false
    }

    private fun isInsideImportList(line: String, lineOffset: Int): Boolean {
        val open = line.indexOf('(')
        val close = line.lastIndexOf(')')
        return open != -1 && close > open && lineOffset in (open + 1) until close
    }

    private fun MatchResult.groupContains(groupIndex: Int, start: Int, end: Int): Boolean {
        val group = groups[groupIndex] ?: return false
        return start >= group.range.first && end <= group.range.last + 1
    }

    private fun isIdentifier(text: String): Boolean = identifierRegex.matches(text)

    private fun isValueIdentifier(text: String): Boolean = text.firstOrNull()?.let { it.isLowerCase() || it == '_' } == true

    private fun isTypeIdentifier(text: String): Boolean = text.firstOrNull()?.isUpperCase() == true

    private fun analysis(fileText: String): Analysis {
        val key = CacheKey(fileText.length, fileText.hashCode())
        synchronized(analysisCache) {
            analysisCache[key]?.let { return it }
            val next = Analysis.build(fileText)
            analysisCache[key] = next
            return next
        }
    }

    private fun abstractMethodNames(fileText: String): Set<String> {
        val names = mutableSetOf<String>()
        var lineStart = 0
        while (lineStart <= fileText.length) {
            val lineEnd = fileText.indexOf('\n', lineStart).let { if (it == -1) fileText.length else it }
            val line = fileText.substring(lineStart, lineEnd)
            val match = abstractMethodRegex.find(line)
            if (match != null && isDirectInterfaceOrClassMember(fileText, lineStart, leadingIndent(line))) {
                match.groups[1]?.value?.let(names::add)
            }
            if (lineEnd == fileText.length) break
            lineStart = lineEnd + 1
        }
        return names
    }

    private fun isPartyDeclaration(line: String, start: Int, end: Int): Boolean =
        sequenceOf(partyBindingRegex.find(line), typedPartyBindingRegex.find(line))
            .filterNotNull()
            .any { it.groupContains(1, start, end) }

    private fun hasEnclosingInterfaceOrClass(fileText: String, lineStart: Int, currentIndent: Int): Boolean {
        for (line in fileText.substring(0, lineStart).lines().asReversed()) {
            val trimmed = line.trim()
            if (trimmed.isEmpty() || trimmed.startsWith("--")) continue

            val indent = leadingIndent(line)
            if (indent >= currentIndent) continue
            if (interfaceOrClassHeaderRegex.containsMatchIn(line)) return true
            if (enclosingDeclarationRegex.containsMatchIn(line)) return false
        }
        return false
    }

    private fun isDirectInterfaceOrClassMember(fileText: String, lineStart: Int, currentIndent: Int): Boolean {
        for (line in fileText.substring(0, lineStart).lines().asReversed()) {
            val trimmed = line.trim()
            if (trimmed.isEmpty() || trimmed.startsWith("--")) continue

            val indent = leadingIndent(line)
            if (indent >= currentIndent) continue

            return Regex("""^\s*(?:interface|class)\s+[A-Z][A-Za-z0-9_']*(?:\s+[a-z_][A-Za-z0-9_']*)*\s+where\b""")
                .containsMatchIn(line)
        }
        return false
    }

    private fun isDirectInterfaceInstanceMember(fileText: String, lineStart: Int, currentIndent: Int): Boolean {
        for (line in fileText.substring(0, lineStart).lines().asReversed()) {
            val trimmed = line.trim()
            if (trimmed.isEmpty() || trimmed.startsWith("--")) continue

            val indent = leadingIndent(line)
            if (indent >= currentIndent) continue

            return interfaceInstanceRegex.containsMatchIn(line)
        }
        return false
    }

    private fun leadingIndent(line: String): Int =
        line.indexOfFirst { !it.isWhitespace() }.let { if (it == -1) line.length else it }

    private data class CacheKey(val length: Int, val hash: Int)

    private data class LineInfo(
        val start: Int,
        val end: Int,
        val text: String,
        val indent: Int
    )

    private data class NameScope(
        val name: String,
        val start: Int,
        val end: Int
    ) {
        fun contains(name: String, offset: Int): Boolean =
            this.name == name && offset in start until end
    }

    private data class Analysis(
        val abstractMethods: Set<String>,
        val partyScopes: List<NameScope>
    ) {
        fun isAbstractMethodUse(
            tokenText: String,
            tokenStart: Int,
            fileText: String,
            lineStart: Int,
            lineIndent: Int
        ): Boolean =
            tokenText in abstractMethods &&
                tokenStart >= 0 &&
                hasEnclosingInterfaceOrClass(fileText, lineStart, lineIndent)

        fun isPartyName(tokenText: String, tokenStart: Int): Boolean =
            partyScopes.any { it.contains(tokenText, tokenStart) }

        companion object {
            fun build(fileText: String): Analysis {
                val lines = lineInfos(fileText)
                return Analysis(
                    abstractMethods = abstractMethodNames(fileText),
                    partyScopes = partyScopes(lines)
                )
            }

            private fun lineInfos(fileText: String): List<LineInfo> {
                val result = mutableListOf<LineInfo>()
                var start = 0
                while (start <= fileText.length) {
                    val end = fileText.indexOf('\n', start).let { if (it == -1) fileText.length else it }
                    val text = fileText.substring(start, end)
                    result += LineInfo(start, end, text, leadingIndent(text))
                    if (end == fileText.length) break
                    start = end + 1
                }
                return result
            }

            private fun partyScopes(lines: List<LineInfo>): List<NameScope> {
                val result = mutableListOf<NameScope>()
                lines.forEachIndexed { index, line ->
                    typedPartyBindingRegex.find(line.text)?.groups?.get(1)?.value?.let { name ->
                        result += NameScope(name, enclosingScopeStart(lines, index), enclosingScopeEnd(lines, index))
                    }
                    partyBindingRegex.find(line.text)?.groups?.get(1)?.value?.let { name ->
                        result += NameScope(name, line.start, localScopeEnd(lines, index))
                    }
                }
                return result
            }

            private fun enclosingScopeStart(lines: List<LineInfo>, index: Int): Int {
                val current = lines[index]
                for (i in index - 1 downTo 0) {
            val line = lines[i]
            val trimmed = line.text.trim()
            if (trimmed.isEmpty() || trimmed.startsWith("--")) continue
            if (line.indent < current.indent &&
                        scopeHeaderRegex.containsMatchIn(trimmed)
                    ) {
                        return line.start
                    }
                }
                return current.start
            }

            private fun enclosingScopeEnd(lines: List<LineInfo>, index: Int): Int {
                val scopeIndent = lines
                    .take(index)
                    .asReversed()
                    .firstOrNull { line ->
                        val trimmed = line.text.trim()
                        trimmed.isNotEmpty() &&
                            !trimmed.startsWith("--") &&
                            line.indent < lines[index].indent &&
                            scopeHeaderRegex.containsMatchIn(trimmed)
                    }
                    ?.indent
                    ?: lines[index].indent
                for (i in index + 1 until lines.size) {
                    val line = lines[i]
                    val trimmed = line.text.trim()
                    if (trimmed.isEmpty() || trimmed.startsWith("--")) continue
                    if (line.indent <= scopeIndent) return line.start
                }
                return lines.lastOrNull()?.end ?: lines[index].end
            }

            private fun localScopeEnd(lines: List<LineInfo>, index: Int): Int {
                val indent = lines[index].indent
                for (i in index + 1 until lines.size) {
                    val line = lines[i]
                    val trimmed = line.text.trim()
                    if (trimmed.isEmpty() || trimmed.startsWith("--")) continue
                    if (line.indent < indent) return line.start
                }
                return lines.lastOrNull()?.end ?: lines[index].end
            }
        }
    }

    private val interfaceOrClassHeaderRegex = Regex(
        """^\s*(?:interface|class)\s+[A-Z][A-Za-z0-9_']*(?:\s+[a-z_][A-Za-z0-9_']*)*\s+where\b"""
    )
    private val enclosingDeclarationRegex = Regex(
        """^\s*(?:template|data|newtype|type|exception)\s+[A-Z][A-Za-z0-9_']*\b"""
    )
    private val scopeHeaderRegex = Regex(
        """^(?:template|interface|data|newtype|interface\s+instance|(?:nonconsuming|preconsuming|postconsuming)\s+choice|choice)\b"""
    )
}
