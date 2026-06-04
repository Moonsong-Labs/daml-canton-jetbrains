package com.moonsonglabs.daml

import com.intellij.lexer.LexerBase
import com.intellij.psi.tree.IElementType

/**
 * Hand-rolled lexer for DAML.
 *
 * Why hand-rolled: a JFlex+GrammarKit pipeline would be overkill for v1; the LSP server
 * provides semantic tokens for fine-grained highlighting. This lexer covers first-paint
 * highlighting (instant, before LSP responds) and the structural needs of IntelliJ's
 * comment/string/word handling.
 *
 * Recognizes: line comments (-- ...), block comments ({- ... -} with nesting), pragmas
 * ({-# ... #-}), doc comments (-- | ... and {-| ... -}), string literals, char literals,
 * numeric literals, keyword groups, booleans, Prelude types/constructors, uppercase
 * identifiers as types, common DAML operators, brackets, and whitespace.
 */
class DamlLexer : LexerBase() {

    private var buffer: CharSequence = ""
    private var startOffset = 0
    private var endOffset = 0
    private var pos = 0
    private var tokenStart = 0
    private var tokenType: IElementType? = null

    override fun start(buffer: CharSequence, startOffset: Int, endOffset: Int, initialState: Int) {
        this.buffer = buffer
        this.startOffset = startOffset
        this.endOffset = endOffset
        this.pos = startOffset
        advance()
    }

    override fun getState(): Int = 0
    override fun getTokenType(): IElementType? = tokenType
    override fun getTokenStart(): Int = tokenStart
    override fun getTokenEnd(): Int = pos
    override fun getBufferSequence(): CharSequence = buffer
    override fun getBufferEnd(): Int = endOffset

    override fun advance() {
        if (pos >= endOffset) { tokenType = null; return }
        tokenStart = pos
        val c = buffer[pos]

        when {
            c.isWhitespace() -> consumeWhitespace()
            isLineCommentStart() -> consumeLineComment()
            c == '{' && peek(1) == '-' && peek(2) == '#' -> consumeBlockBalanced(DamlTokenTypes.PRAGMA, "#-}")
            c == '{' && peek(1) == '-' && peek(2) == '|' -> consumeNestedBlockComment(DamlTokenTypes.DOC_COMMENT)
            c == '{' && peek(1) == '-' -> consumeNestedBlockComment(DamlTokenTypes.BLOCK_COMMENT)
            c == '"' -> consumeStringLiteral()
            c == '\'' -> consumeCharLiteral()
            c.isDigit() -> consumeNumber()
            c == '(' && peek(1) == ')' -> { pos += 2; tokenType = DamlTokenTypes.UNIT_LITERAL }
            c == '[' && peek(1) == ']' -> { pos += 2; tokenType = DamlTokenTypes.EMPTY_LIST_LITERAL }
            c == '(' -> { pos++; tokenType = DamlTokenTypes.LPAREN }
            c == ')' -> { pos++; tokenType = DamlTokenTypes.RPAREN }
            c == '{' -> { pos++; tokenType = DamlTokenTypes.LBRACE }
            c == '}' -> { pos++; tokenType = DamlTokenTypes.RBRACE }
            c == '[' -> { pos++; tokenType = DamlTokenTypes.LBRACKET }
            c == ']' -> { pos++; tokenType = DamlTokenTypes.RBRACKET }
            c == ',' -> { pos++; tokenType = DamlTokenTypes.COMMA }
            c == ';' -> { pos++; tokenType = DamlTokenTypes.SEMICOLON }
            c == '`' -> { pos++; tokenType = DamlTokenTypes.BACKTICK }
            isIdStart(c) -> consumeIdentifier()
            isOpChar(c) -> consumeOperator()
            else -> { pos++; tokenType = DamlTokenTypes.BAD_CHARACTER }
        }
    }

    private fun peek(offset: Int): Char = if (pos + offset < endOffset) buffer[pos + offset] else '\u0000'

    private fun consumeWhitespace() {
        while (pos < endOffset && buffer[pos].isWhitespace()) pos++
        tokenType = DamlTokenTypes.WHITE_SPACE
    }

    private fun consumeLineComment() {
        val isDoc = peek(2) == ' ' && peek(3) == '|' || peek(2) == '|'
        while (pos < endOffset && buffer[pos] != '\n') pos++
        tokenType = if (isDoc) DamlTokenTypes.DOC_COMMENT else DamlTokenTypes.LINE_COMMENT
    }

    private fun consumeNestedBlockComment(type: IElementType) {
        pos += 2  // {-
        var depth = 1
        while (pos < endOffset && depth > 0) {
            val a = buffer[pos]
            val b = if (pos + 1 < endOffset) buffer[pos + 1] else '\u0000'
            when {
                a == '{' && b == '-' -> { depth++; pos += 2 }
                a == '-' && b == '}' -> { depth--; pos += 2 }
                else -> pos++
            }
        }
        tokenType = type
    }

    private fun consumeBlockBalanced(type: IElementType, terminator: String) {
        val tlen = terminator.length
        pos += 3  // {-#
        while (pos + tlen <= endOffset) {
            if (matches(terminator)) { pos += tlen; tokenType = type; return }
            pos++
        }
        pos = endOffset
        tokenType = type
    }

    private fun matches(s: String): Boolean {
        if (pos + s.length > endOffset) return false
        for (i in s.indices) if (buffer[pos + i] != s[i]) return false
        return true
    }

    private fun consumeStringLiteral() {
        pos++  // opening "
        while (pos < endOffset) {
            val c = buffer[pos]
            if (c == '\\' && pos + 1 < endOffset) { pos += 2; continue }
            if (c == '"') { pos++; break }
            if (c == '\n') break
            pos++
        }
        tokenType = DamlTokenTypes.STRING_LITERAL
    }

    private fun consumeCharLiteral() {
        pos++  // opening '
        var seenContent = false
        while (pos < endOffset) {
            val c = buffer[pos]
            if (c == '\\' && pos + 1 < endOffset) { pos += 2; seenContent = true; continue }
            if (c == '\'') { pos++; break }
            if (c == '\n') break
            pos++; seenContent = true
        }
        // Heuristic: if no content or longer than ~10 chars without close, treat as operator backtick-style.
        tokenType = if (seenContent) DamlTokenTypes.CHAR_LITERAL else DamlTokenTypes.OPERATOR
    }

    private fun consumeNumber() {
        // hex/oct/bin prefixes
        if (buffer[pos] == '0' && pos + 1 < endOffset) {
            val n = buffer[pos + 1]
            if (n == 'x' || n == 'X' || n == 'o' || n == 'O' || n == 'b' || n == 'B') {
                pos += 2
                while (pos < endOffset && (buffer[pos].isLetterOrDigit() || buffer[pos] == '_')) pos++
                tokenType = DamlTokenTypes.NUMBER
                return
            }
        }
        while (pos < endOffset && (buffer[pos].isDigit() || buffer[pos] == '_')) pos++
        if (pos < endOffset && buffer[pos] == '.' && pos + 1 < endOffset && buffer[pos + 1].isDigit()) {
            pos++
            while (pos < endOffset && (buffer[pos].isDigit() || buffer[pos] == '_')) pos++
        }
        if (pos < endOffset && (buffer[pos] == 'e' || buffer[pos] == 'E')) {
            pos++
            if (pos < endOffset && (buffer[pos] == '+' || buffer[pos] == '-')) pos++
            while (pos < endOffset && buffer[pos].isDigit()) pos++
        }
        tokenType = DamlTokenTypes.NUMBER
    }

    private fun consumeIdentifier() {
        val firstChar = buffer[pos]
        while (pos < endOffset && isIdCont(buffer[pos])) pos++
        val text = buffer.subSequence(tokenStart, pos).toString()
        tokenType = when {
            text in DamlKeywords.booleanLiterals -> DamlTokenTypes.BOOLEAN_LITERAL
            text in DamlKeywords.controlKeywords -> DamlTokenTypes.CONTROL_KEYWORD
            text in DamlKeywords.choiceModifierKeywords -> DamlTokenTypes.CHOICE_MODIFIER_KEYWORD
            text in DamlKeywords.moduleKeywords -> DamlTokenTypes.MODULE_KEYWORD
            text in DamlKeywords.importKeywords -> DamlTokenTypes.IMPORT_KEYWORD
            text in DamlKeywords.contractClauseKeywords -> DamlTokenTypes.DAML_KEYWORD
            text in DamlKeywords.declarationKeywords -> DamlTokenTypes.DECLARATION_KEYWORD
            text in DamlKeywords.haskellKeywords || text in DamlKeywords.damlKeywords -> DamlTokenTypes.KEYWORD
            text in DamlKeywords.predefinedConstructors -> DamlTokenTypes.PREDEFINED_IDENTIFIER
            text in DamlKeywords.preludeTypes -> DamlTokenTypes.PRELUDE_TYPE
            text in DamlKeywords.builtins -> DamlTokenTypes.BUILTIN_IDENTIFIER
            firstChar.isUpperCase() -> DamlTokenTypes.TYPE_NAME
            else -> DamlTokenTypes.IDENTIFIER
        }
    }

    private fun consumeOperator() {
        while (pos < endOffset && isOpChar(buffer[pos])) pos++
        val text = buffer.subSequence(tokenStart, pos).toString()
        tokenType = when (text) {
            "." -> DamlTokenTypes.DOT
            ":" -> DamlTokenTypes.COLON
            "::" -> DamlTokenTypes.DOUBLE_COLON
            "->", "\u2192" -> DamlTokenTypes.ARROW
            "=>", "\u21d2" -> DamlTokenTypes.BIG_ARROW
            "<-" -> DamlTokenTypes.BIND_ARROW
            "=" -> DamlTokenTypes.EQUALS
            "==", "/=" -> DamlTokenTypes.EQUALITY_OPERATOR
            else -> DamlTokenTypes.OPERATOR
        }
    }

    private fun isIdStart(c: Char): Boolean = c.isLetter() || c == '_'
    private fun isIdCont(c: Char): Boolean = c.isLetterOrDigit() || c == '_' || c == '\''

    private fun isLineCommentStart(): Boolean {
        if (buffer[pos] != '-' || peek(1) != '-') return false
        val next = peek(2)
        return next == '\u0000' || next == '\n' || next == '\r' || next == '|' || next.isWhitespace() || !isOpChar(next)
    }

    private fun isOpChar(c: Char): Boolean = when (c) {
        '!', '#', '$', '%', '&', '*', '+', '.', '/', '<', '=', '>', '?', '@', '\\', '^', '|', '-', '~', ':',
        '\u2192', '\u21d2' -> true
        else -> false
    }
}
