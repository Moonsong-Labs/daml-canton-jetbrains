package com.moonsonglabs.daml.canton

import com.intellij.lexer.Lexer
import com.intellij.openapi.editor.DefaultLanguageHighlighterColors
import com.intellij.openapi.editor.HighlighterColors
import com.intellij.openapi.editor.colors.TextAttributesKey
import com.intellij.openapi.fileTypes.SyntaxHighlighterBase
import com.intellij.psi.tree.IElementType

class CantonSyntaxHighlighter : SyntaxHighlighterBase() {
    override fun getHighlightingLexer(): Lexer = CantonLexer()

    override fun getTokenHighlights(tokenType: IElementType?): Array<TextAttributesKey> = when (tokenType) {
        CantonTokenTypes.COMMENT -> arrayOf(COMMENT)
        CantonTokenTypes.KEYWORD -> arrayOf(KEYWORD)
        CantonTokenTypes.STRING -> arrayOf(STRING)
        CantonTokenTypes.NUMBER -> arrayOf(NUMBER)
        CantonTokenTypes.BRACE -> arrayOf(BRACES)
        CantonTokenTypes.BRACKET -> arrayOf(BRACKETS)
        CantonTokenTypes.OPERATOR -> arrayOf(OPERATOR)
        CantonTokenTypes.BAD_CHARACTER -> arrayOf(HighlighterColors.BAD_CHARACTER)
        else -> emptyArray()
    }

    companion object {
        val COMMENT = TextAttributesKey.createTextAttributesKey("CANTON_COMMENT", DefaultLanguageHighlighterColors.LINE_COMMENT)
        val KEYWORD = TextAttributesKey.createTextAttributesKey("CANTON_KEYWORD", DefaultLanguageHighlighterColors.KEYWORD)
        val STRING = TextAttributesKey.createTextAttributesKey("CANTON_STRING", DefaultLanguageHighlighterColors.STRING)
        val NUMBER = TextAttributesKey.createTextAttributesKey("CANTON_NUMBER", DefaultLanguageHighlighterColors.NUMBER)
        val BRACES = TextAttributesKey.createTextAttributesKey("CANTON_BRACES", DefaultLanguageHighlighterColors.BRACES)
        val BRACKETS = TextAttributesKey.createTextAttributesKey("CANTON_BRACKETS", DefaultLanguageHighlighterColors.BRACKETS)
        val OPERATOR = TextAttributesKey.createTextAttributesKey("CANTON_OPERATOR", DefaultLanguageHighlighterColors.OPERATION_SIGN)
    }
}
