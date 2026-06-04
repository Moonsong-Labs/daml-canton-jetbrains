package com.moonsonglabs.daml.navigation

object DamlChoiceNames {
    private val choiceDeclarationRegex = Regex(
        """(?m)^\s*(?:(?:nonconsuming|preconsuming|postconsuming)\s+)?choice\s+([A-Z][A-Za-z0-9_']*)\b"""
    )
    private val identifierRegex = Regex("""[A-Za-z_][A-Za-z0-9_']*""")
    private val exerciseCallRegex = Regex(
        """\b(?:exercise|exerciseCmd|exerciseExactCmd|exerciseByKey|exerciseByKeyCmd|exerciseByKeyExactCmd|exerciseInterface|exerciseInterfaceCmd|exerciseByKeyInterface|exerciseByKeyInterfaceCmd|createAndExercise|createAndExerciseCmd|createAndExerciseExactCmd|createAndExerciseWithCidCmd|createAndExerciseWithCidExactCmd)\b"""
    )

    data class ChoiceDeclaration(val name: String, val startOffset: Int)
    data class ChoiceUse(val name: String, val startOffset: Int, val endOffset: Int)

    fun declarations(text: String): List<ChoiceDeclaration> =
        choiceDeclarationRegex.findAll(text)
            .mapNotNull { match ->
                match.groups[1]?.let { group -> ChoiceDeclaration(group.value, group.range.first) }
            }
            .toList()

    fun declarationNamed(text: String, choiceName: String): ChoiceDeclaration? =
        declarations(text).firstOrNull { it.name == choiceName.trim('`') }

    fun declarationAt(text: String, offset: Int): ChoiceDeclaration? {
        val token = identifierAt(text, offset) ?: return null
        return declarations(text).firstOrNull { declaration ->
            declaration.name == token.name && declaration.startOffset == token.startOffset
        }
    }

    fun uses(text: String): List<ChoiceUse> =
        identifierRegex.findAll(text)
            .mapNotNull { match -> useAt(text, match.range.first) }
            .distinctBy { it.startOffset }
            .toList()

    fun useAt(text: String, offset: Int): ChoiceUse? {
        if (text.isEmpty()) return null
        if (!DamlModuleNames.isCodePosition(text, offset)) return null
        val token = identifierAt(text, offset) ?: return null
        if (!token.name.first().isUpperCase()) return null
        if (token.endOffset < text.length && text[token.endOffset] == '.') return null
        if (!isExerciseChoicePosition(text, token.startOffset)) return null
        return token
    }

    private fun identifierAt(text: String, offset: Int): ChoiceUse? {
        val clamped = offset.coerceIn(0, text.lastIndex.coerceAtLeast(0))
        if (!isIdentifierPart(text[clamped])) return null

        var start = clamped
        while (start > 0 && isIdentifierPart(text[start - 1])) start--
        var end = clamped + 1
        while (end < text.length && isIdentifierPart(text[end])) end++

        val name = text.substring(start, end)
        if (!identifierRegex.matches(name)) return null
        return ChoiceUse(name, start, end)
    }

    private fun isExerciseChoicePosition(text: String, choiceStart: Int): Boolean {
        val windowStart = previousStatementBoundary(text, choiceStart)
        val prefix = text.substring(windowStart, choiceStart)
        val call = exerciseCallRegex.findAll(prefix).lastOrNull() ?: return false
        val argumentsBeforeChoice = prefix.substring(call.range.last + 1)
        return hasCompletedArgumentBeforeChoice(argumentsBeforeChoice)
    }

    private fun previousStatementBoundary(text: String, offset: Int): Int {
        val currentIndent = indentationAt(text, offset)
        var lineStart = text.lastIndexOf('\n', (offset - 1).coerceAtLeast(0)).let { if (it == -1) 0 else it + 1 }
        var cursor = lineStart
        while (cursor > 0) {
            val previousLineEnd = cursor - 1
            val previousLineStart = text.lastIndexOf('\n', (previousLineEnd - 1).coerceAtLeast(0)).let {
                if (it == -1) 0 else it + 1
            }
            val previousLine = text.substring(previousLineStart, previousLineEnd).trim()
            if (previousLine.isEmpty()) return cursor
            if (';' in previousLine) return previousLineStart + previousLine.lastIndexOf(';') + 1
            val previousIndent = indentationAt(text, previousLineStart)
            if (previousIndent < currentIndent &&
                !exerciseCallRegex.containsMatchIn(previousLine) &&
                !previousLine.endsWith("$") &&
                !previousLine.endsWith("(") &&
                !previousLine.endsWith(",")
            ) {
                return cursor
            }
            cursor = previousLineStart
        }
        return 0
    }

    private fun indentationAt(text: String, offset: Int): Int {
        val lineStart = text.lastIndexOf('\n', (offset - 1).coerceAtLeast(0)).let { if (it == -1) 0 else it + 1 }
        var cursor = lineStart
        while (cursor < text.length && text[cursor].isWhitespace() && text[cursor] != '\n') cursor++
        return cursor - lineStart
    }

    private fun hasCompletedArgumentBeforeChoice(text: String): Boolean {
        val withoutTypeApplications = text
            .replace(Regex("""@[A-Za-z_][A-Za-z0-9_'.]*"""), " ")
            .replace(Regex("""(?:[A-Za-z_][A-Za-z0-9_']*\.)+$"""), "")
        return Regex("""[A-Za-z0-9_')\]}][\s\n]+$""").containsMatchIn(withoutTypeApplications)
    }

    private fun isIdentifierPart(c: Char): Boolean =
        c.isLetterOrDigit() || c == '_' || c == '\''
}
