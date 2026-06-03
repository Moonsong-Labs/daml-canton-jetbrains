package com.moonsonglabs.daml.syntax

import com.intellij.lexer.Lexer
import com.intellij.openapi.editor.DefaultLanguageHighlighterColors
import com.intellij.openapi.editor.HighlighterColors
import com.intellij.openapi.editor.colors.TextAttributesKey
import com.intellij.openapi.fileTypes.SyntaxHighlighterBase
import com.intellij.psi.tree.IElementType
import com.moonsonglabs.daml.DamlLexer
import com.moonsonglabs.daml.DamlTokenTypes

class DamlSyntaxHighlighter : SyntaxHighlighterBase() {
    override fun getHighlightingLexer(): Lexer = DamlLexer()

    override fun getTokenHighlights(tokenType: IElementType?): Array<TextAttributesKey> = when (tokenType) {
        DamlTokenTypes.LINE_COMMENT -> arrayOf(LINE_COMMENT)
        DamlTokenTypes.BLOCK_COMMENT -> arrayOf(BLOCK_COMMENT)
        DamlTokenTypes.DOC_COMMENT -> arrayOf(DOC_COMMENT)
        DamlTokenTypes.PRAGMA -> arrayOf(PRAGMA)
        DamlTokenTypes.KEYWORD -> arrayOf(KEYWORD)
        DamlTokenTypes.MODULE_KEYWORD -> arrayOf(MODULE_KEYWORD)
        DamlTokenTypes.IMPORT_KEYWORD -> arrayOf(IMPORT_KEYWORD)
        DamlTokenTypes.DECLARATION_KEYWORD -> arrayOf(DECLARATION_KEYWORD)
        DamlTokenTypes.DAML_KEYWORD -> arrayOf(CONTRACT_CLAUSE_KEYWORD)
        DamlTokenTypes.CHOICE_MODIFIER_KEYWORD -> arrayOf(CHOICE_MODIFIER_KEYWORD)
        DamlTokenTypes.CONTROL_KEYWORD -> arrayOf(CONTROL_KEYWORD)
        DamlTokenTypes.TYPE_NAME -> arrayOf(TYPE_NAME)
        DamlTokenTypes.PRELUDE_TYPE -> arrayOf(PRELUDE_TYPE)
        DamlTokenTypes.IDENTIFIER -> arrayOf(IDENTIFIER)
        DamlTokenTypes.BUILTIN_IDENTIFIER -> arrayOf(BUILTIN)
        DamlTokenTypes.PREDEFINED_IDENTIFIER -> arrayOf(CONSTRUCTOR)
        DamlTokenTypes.STRING_LITERAL -> arrayOf(STRING)
        DamlTokenTypes.CHAR_LITERAL -> arrayOf(STRING)
        DamlTokenTypes.NUMBER -> arrayOf(NUMBER)
        DamlTokenTypes.BOOLEAN_LITERAL -> arrayOf(BOOLEAN)
        DamlTokenTypes.UNIT_LITERAL, DamlTokenTypes.EMPTY_LIST_LITERAL -> arrayOf(PREDEFINED_VALUE)
        DamlTokenTypes.OPERATOR -> arrayOf(OPERATOR)
        DamlTokenTypes.DOT -> arrayOf(DOT)
        DamlTokenTypes.COLON, DamlTokenTypes.DOUBLE_COLON -> arrayOf(TYPE_OPERATOR)
        DamlTokenTypes.ARROW, DamlTokenTypes.BIG_ARROW, DamlTokenTypes.BIND_ARROW -> arrayOf(TYPE_OPERATOR)
        DamlTokenTypes.EQUALS, DamlTokenTypes.EQUALITY_OPERATOR -> arrayOf(OPERATOR)
        DamlTokenTypes.LPAREN, DamlTokenTypes.RPAREN -> arrayOf(PARENS)
        DamlTokenTypes.LBRACE, DamlTokenTypes.RBRACE -> arrayOf(BRACES)
        DamlTokenTypes.LBRACKET, DamlTokenTypes.RBRACKET -> arrayOf(BRACKETS)
        DamlTokenTypes.COMMA -> arrayOf(COMMA)
        DamlTokenTypes.SEMICOLON -> arrayOf(SEMICOLON)
        DamlTokenTypes.BACKTICK -> arrayOf(OPERATOR)
        DamlTokenTypes.BAD_CHARACTER -> arrayOf(HighlighterColors.BAD_CHARACTER)
        else -> emptyArray()
    }

    companion object {
        val LINE_COMMENT = TextAttributesKey.createTextAttributesKey(
            "DAML_LINE_COMMENT", DefaultLanguageHighlighterColors.LINE_COMMENT)
        val BLOCK_COMMENT = TextAttributesKey.createTextAttributesKey(
            "DAML_BLOCK_COMMENT", DefaultLanguageHighlighterColors.BLOCK_COMMENT)
        val DOC_COMMENT = TextAttributesKey.createTextAttributesKey(
            "DAML_DOC_COMMENT", DefaultLanguageHighlighterColors.DOC_COMMENT)
        val PRAGMA = TextAttributesKey.createTextAttributesKey(
            "DAML_PRAGMA", DefaultLanguageHighlighterColors.METADATA)
        val KEYWORD = TextAttributesKey.createTextAttributesKey(
            "DAML_KEYWORD", DefaultLanguageHighlighterColors.KEYWORD)
        val MODULE_KEYWORD = TextAttributesKey.createTextAttributesKey(
            "DAML_MODULE_KEYWORD", DefaultLanguageHighlighterColors.KEYWORD)
        val IMPORT_KEYWORD = TextAttributesKey.createTextAttributesKey(
            "DAML_IMPORT_KEYWORD", DefaultLanguageHighlighterColors.KEYWORD)
        val DECLARATION_KEYWORD = TextAttributesKey.createTextAttributesKey(
            "DAML_DECLARATION_KEYWORD", DefaultLanguageHighlighterColors.KEYWORD)
        val CONTRACT_CLAUSE_KEYWORD = TextAttributesKey.createTextAttributesKey(
            "DAML_TEMPLATE_KEYWORD", DefaultLanguageHighlighterColors.KEYWORD)
        val CHOICE_MODIFIER_KEYWORD = TextAttributesKey.createTextAttributesKey(
            "DAML_CHOICE_MODIFIER_KEYWORD", DefaultLanguageHighlighterColors.KEYWORD)
        val CONTROL_KEYWORD = TextAttributesKey.createTextAttributesKey(
            "DAML_CONTROL_KEYWORD", DefaultLanguageHighlighterColors.KEYWORD)
        val TYPE_NAME = TextAttributesKey.createTextAttributesKey(
            "DAML_TYPE_NAME", DefaultLanguageHighlighterColors.FUNCTION_DECLARATION)
        val PRELUDE_TYPE = TextAttributesKey.createTextAttributesKey(
            "DAML_PRELUDE_TYPE", TYPE_NAME)
        val IDENTIFIER = TextAttributesKey.createTextAttributesKey(
            "DAML_IDENTIFIER", DefaultLanguageHighlighterColors.IDENTIFIER)
        val MODULE_NAME = TextAttributesKey.createTextAttributesKey(
            "DAML_MODULE_NAME", DefaultLanguageHighlighterColors.CLASS_REFERENCE)
        val DECLARATION_NAME = TextAttributesKey.createTextAttributesKey(
            "DAML_DECLARATION_NAME", DefaultLanguageHighlighterColors.FUNCTION_DECLARATION)
        val CHOICE_NAME = TextAttributesKey.createTextAttributesKey(
            "DAML_CHOICE_NAME", DefaultLanguageHighlighterColors.INSTANCE_METHOD)
        val FIELD_NAME = TextAttributesKey.createTextAttributesKey(
            "DAML_FIELD_NAME", DefaultLanguageHighlighterColors.INSTANCE_FIELD)
        val TYPE_PARAMETER = TextAttributesKey.createTextAttributesKey(
            "DAML_TYPE_PARAMETER", DefaultLanguageHighlighterColors.PARAMETER)
        val IMPORT_SYMBOL = TextAttributesKey.createTextAttributesKey(
            "DAML_IMPORT_SYMBOL", DefaultLanguageHighlighterColors.CLASS_REFERENCE)
        val SCRIPT_DECLARATION = TextAttributesKey.createTextAttributesKey(
            "DAML_SCRIPT_DECLARATION", DefaultLanguageHighlighterColors.FUNCTION_DECLARATION)
        val ABSTRACT_METHOD = TextAttributesKey.createTextAttributesKey(
            "DAML_ABSTRACT_METHOD", DefaultLanguageHighlighterColors.INSTANCE_METHOD)
        val BUILTIN = TextAttributesKey.createTextAttributesKey(
            "DAML_BUILTIN", DefaultLanguageHighlighterColors.KEYWORD)
        val PARTY_NAME = TextAttributesKey.createTextAttributesKey(
            "DAML_PARTY_NAME", DefaultLanguageHighlighterColors.CONSTANT)
        val CONSTRUCTOR = TextAttributesKey.createTextAttributesKey(
            "DAML_CONSTRUCTOR", DefaultLanguageHighlighterColors.STATIC_FIELD)
        val PREDEFINED_VALUE = TextAttributesKey.createTextAttributesKey(
            "DAML_PREDEFINED_VALUE", DefaultLanguageHighlighterColors.PREDEFINED_SYMBOL)
        val THIS_REFERENCE = TextAttributesKey.createTextAttributesKey(
            "DAML_THIS_REFERENCE", DefaultLanguageHighlighterColors.PREDEFINED_SYMBOL)
        val STRING = TextAttributesKey.createTextAttributesKey(
            "DAML_STRING", DefaultLanguageHighlighterColors.STRING)
        val NUMBER = TextAttributesKey.createTextAttributesKey(
            "DAML_NUMBER", DefaultLanguageHighlighterColors.NUMBER)
        val BOOLEAN = TextAttributesKey.createTextAttributesKey(
            "DAML_BOOLEAN", DefaultLanguageHighlighterColors.PREDEFINED_SYMBOL)
        val OPERATOR = TextAttributesKey.createTextAttributesKey(
            "DAML_OPERATOR", DefaultLanguageHighlighterColors.OPERATION_SIGN)
        val TYPE_OPERATOR = TextAttributesKey.createTextAttributesKey(
            "DAML_TYPE_OPERATOR", DefaultLanguageHighlighterColors.OPERATION_SIGN)
        val DOT = TextAttributesKey.createTextAttributesKey(
            "DAML_DOT", DefaultLanguageHighlighterColors.DOT)
        val PARENS = TextAttributesKey.createTextAttributesKey(
            "DAML_PARENS", DefaultLanguageHighlighterColors.PARENTHESES)
        val BRACES = TextAttributesKey.createTextAttributesKey(
            "DAML_BRACES", DefaultLanguageHighlighterColors.BRACES)
        val BRACKETS = TextAttributesKey.createTextAttributesKey(
            "DAML_BRACKETS", DefaultLanguageHighlighterColors.BRACKETS)
        val COMMA = TextAttributesKey.createTextAttributesKey(
            "DAML_COMMA", DefaultLanguageHighlighterColors.COMMA)
        val SEMICOLON = TextAttributesKey.createTextAttributesKey(
            "DAML_SEMICOLON", DefaultLanguageHighlighterColors.SEMICOLON)
    }
}
