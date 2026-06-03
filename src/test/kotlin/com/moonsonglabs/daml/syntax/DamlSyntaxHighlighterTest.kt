package com.moonsonglabs.daml.syntax

import com.intellij.openapi.editor.colors.TextAttributesKey
import com.intellij.psi.tree.IElementType
import com.moonsonglabs.daml.DamlTokenTypes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class DamlSyntaxHighlighterTest {
    private val highlighter = DamlSyntaxHighlighter()

    @Test
    fun `maps expanded token groups to DAML color keys`() {
        assertHighlights(DamlTokenTypes.MODULE_KEYWORD, DamlSyntaxHighlighter.MODULE_KEYWORD)
        assertHighlights(DamlTokenTypes.IMPORT_KEYWORD, DamlSyntaxHighlighter.IMPORT_KEYWORD)
        assertHighlights(DamlTokenTypes.DECLARATION_KEYWORD, DamlSyntaxHighlighter.DECLARATION_KEYWORD)
        assertHighlights(DamlTokenTypes.DAML_KEYWORD, DamlSyntaxHighlighter.CONTRACT_CLAUSE_KEYWORD)
        assertHighlights(DamlTokenTypes.CHOICE_MODIFIER_KEYWORD, DamlSyntaxHighlighter.CHOICE_MODIFIER_KEYWORD)
        assertHighlights(DamlTokenTypes.CONTROL_KEYWORD, DamlSyntaxHighlighter.CONTROL_KEYWORD)
        assertHighlights(DamlTokenTypes.PRELUDE_TYPE, DamlSyntaxHighlighter.PRELUDE_TYPE)
        assertHighlights(DamlTokenTypes.BUILTIN_IDENTIFIER, DamlSyntaxHighlighter.BUILTIN)
        assertHighlights(DamlTokenTypes.PREDEFINED_IDENTIFIER, DamlSyntaxHighlighter.CONSTRUCTOR)
        assertHighlights(DamlTokenTypes.BOOLEAN_LITERAL, DamlSyntaxHighlighter.BOOLEAN)
        assertHighlights(DamlTokenTypes.UNIT_LITERAL, DamlSyntaxHighlighter.PREDEFINED_VALUE)
        assertHighlights(DamlTokenTypes.EMPTY_LIST_LITERAL, DamlSyntaxHighlighter.PREDEFINED_VALUE)
        assertHighlights(DamlTokenTypes.ARROW, DamlSyntaxHighlighter.TYPE_OPERATOR)
        assertHighlights(DamlTokenTypes.BIND_ARROW, DamlSyntaxHighlighter.TYPE_OPERATOR)
        assertHighlights(DamlTokenTypes.DOT, DamlSyntaxHighlighter.DOT)
    }

    @Test
    fun `uses visible defaults for types constructors builtins and party names`() {
        assertSame(
            com.intellij.openapi.editor.DefaultLanguageHighlighterColors.FUNCTION_DECLARATION,
            DamlSyntaxHighlighter.TYPE_NAME.fallbackAttributeKey
        )
        assertSame(
            DamlSyntaxHighlighter.TYPE_NAME,
            DamlSyntaxHighlighter.PRELUDE_TYPE.fallbackAttributeKey
        )
        assertSame(
            com.intellij.openapi.editor.DefaultLanguageHighlighterColors.STATIC_FIELD,
            DamlSyntaxHighlighter.CONSTRUCTOR.fallbackAttributeKey
        )
        assertSame(
            com.intellij.openapi.editor.DefaultLanguageHighlighterColors.KEYWORD,
            DamlSyntaxHighlighter.BUILTIN.fallbackAttributeKey
        )
        assertSame(
            com.intellij.openapi.editor.DefaultLanguageHighlighterColors.CONSTANT,
            DamlSyntaxHighlighter.PARTY_NAME.fallbackAttributeKey
        )
    }

    private fun assertHighlights(tokenType: IElementType, key: TextAttributesKey) {
        assertEquals(listOf(key), highlighter.getTokenHighlights(tokenType).toList())
    }
}
