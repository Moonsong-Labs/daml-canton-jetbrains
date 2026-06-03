package com.moonsonglabs.daml.canton

import com.intellij.lexer.LexerBase
import com.intellij.psi.tree.IElementType

class CantonLexer : LexerBase() {
    private var buffer: CharSequence = ""
    private var endOffset = 0
    private var pos = 0
    private var tokenStart = 0
    private var tokenType: IElementType? = null

    override fun start(buffer: CharSequence, startOffset: Int, endOffset: Int, initialState: Int) {
        this.buffer = buffer
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
        if (pos >= endOffset) {
            tokenType = null
            return
        }
        tokenStart = pos
        val c = buffer[pos]
        when {
            c.isWhitespace() -> consumeWhitespace()
            c == '#' || (c == '/' && peek(1) == '/') -> consumeLineComment()
            c == '"' -> consumeString()
            c.isDigit() -> consumeNumber()
            c == '{' || c == '}' -> { pos++; tokenType = CantonTokenTypes.BRACE }
            c == '[' || c == ']' || c == '(' || c == ')' -> { pos++; tokenType = CantonTokenTypes.BRACKET }
            c.isLetter() || c == '_' -> consumeIdentifier()
            isOperator(c) -> consumeOperator()
            else -> { pos++; tokenType = CantonTokenTypes.BAD_CHARACTER }
        }
    }

    private fun peek(offset: Int): Char = if (pos + offset < endOffset) buffer[pos + offset] else '\u0000'

    private fun consumeWhitespace() {
        while (pos < endOffset && buffer[pos].isWhitespace()) pos++
        tokenType = CantonTokenTypes.WHITE_SPACE
    }

    private fun consumeLineComment() {
        while (pos < endOffset && buffer[pos] != '\n') pos++
        tokenType = CantonTokenTypes.COMMENT
    }

    private fun consumeString() {
        pos++
        while (pos < endOffset) {
            val c = buffer[pos]
            if (c == '\\' && pos + 1 < endOffset) {
                pos += 2
                continue
            }
            if (c == '"') {
                pos++
                break
            }
            pos++
        }
        tokenType = CantonTokenTypes.STRING
    }

    private fun consumeNumber() {
        while (pos < endOffset && (buffer[pos].isDigit() || buffer[pos] == '.')) pos++
        tokenType = CantonTokenTypes.NUMBER
    }

    private fun consumeIdentifier() {
        while (pos < endOffset && (buffer[pos].isLetterOrDigit() || buffer[pos] == '_' || buffer[pos] == '-' || buffer[pos] == '.')) pos++
        val text = buffer.subSequence(tokenStart, pos).toString()
        tokenType = if (text in CantonTokenTypes.keywords) CantonTokenTypes.KEYWORD else CantonTokenTypes.IDENTIFIER
    }

    private fun consumeOperator() {
        while (pos < endOffset && isOperator(buffer[pos])) pos++
        tokenType = CantonTokenTypes.OPERATOR
    }

    private fun isOperator(c: Char): Boolean = c in "=:+-*/,<>"
}
