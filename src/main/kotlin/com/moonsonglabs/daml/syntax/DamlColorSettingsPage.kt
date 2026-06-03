package com.moonsonglabs.daml.syntax

import com.intellij.openapi.editor.colors.TextAttributesKey
import com.intellij.openapi.options.colors.AttributesDescriptor
import com.intellij.openapi.options.colors.ColorDescriptor
import com.intellij.openapi.options.colors.ColorSettingsPage
import com.moonsonglabs.daml.DamlIcons
import javax.swing.Icon

class DamlColorSettingsPage : ColorSettingsPage {
    override fun getDisplayName(): String = "DAML"

    override fun getIcon(): Icon = DamlIcons.File

    override fun getHighlighter() = DamlSyntaxHighlighter()

    override fun getAttributeDescriptors(): Array<AttributesDescriptor> = DESCRIPTORS

    override fun getColorDescriptors(): Array<ColorDescriptor> = ColorDescriptor.EMPTY_ARRAY

    override fun getAdditionalHighlightingTagToDescriptorMap(): Map<String, TextAttributesKey> = TAGS

    override fun getDemoText(): String = DEMO_TEXT

    companion object {
        private val DESCRIPTORS = arrayOf(
            AttributesDescriptor("Comments//Line comment", DamlSyntaxHighlighter.LINE_COMMENT),
            AttributesDescriptor("Comments//Block comment", DamlSyntaxHighlighter.BLOCK_COMMENT),
            AttributesDescriptor("Comments//Documentation comment", DamlSyntaxHighlighter.DOC_COMMENT),
            AttributesDescriptor("Pragmas", DamlSyntaxHighlighter.PRAGMA),
            AttributesDescriptor("Keywords//General", DamlSyntaxHighlighter.KEYWORD),
            AttributesDescriptor("Keywords//Module", DamlSyntaxHighlighter.MODULE_KEYWORD),
            AttributesDescriptor("Keywords//Import", DamlSyntaxHighlighter.IMPORT_KEYWORD),
            AttributesDescriptor("Keywords//Declaration", DamlSyntaxHighlighter.DECLARATION_KEYWORD),
            AttributesDescriptor("Keywords//Contract clause", DamlSyntaxHighlighter.CONTRACT_CLAUSE_KEYWORD),
            AttributesDescriptor("Keywords//Choice modifier", DamlSyntaxHighlighter.CHOICE_MODIFIER_KEYWORD),
            AttributesDescriptor("Keywords//Control flow", DamlSyntaxHighlighter.CONTROL_KEYWORD),
            AttributesDescriptor("Names//Type", DamlSyntaxHighlighter.TYPE_NAME),
            AttributesDescriptor("Names//Native type", DamlSyntaxHighlighter.PRELUDE_TYPE),
            AttributesDescriptor("Names//Module", DamlSyntaxHighlighter.MODULE_NAME),
            AttributesDescriptor("Names//Declaration", DamlSyntaxHighlighter.DECLARATION_NAME),
            AttributesDescriptor("Names//Choice", DamlSyntaxHighlighter.CHOICE_NAME),
            AttributesDescriptor("Names//Field", DamlSyntaxHighlighter.FIELD_NAME),
            AttributesDescriptor("Names//Type parameter", DamlSyntaxHighlighter.TYPE_PARAMETER),
            AttributesDescriptor("Names//Import symbol", DamlSyntaxHighlighter.IMPORT_SYMBOL),
            AttributesDescriptor("Names//Script declaration", DamlSyntaxHighlighter.SCRIPT_DECLARATION),
            AttributesDescriptor("Names//Abstract method", DamlSyntaxHighlighter.ABSTRACT_METHOD),
            AttributesDescriptor("Names//Built-in", DamlSyntaxHighlighter.BUILTIN),
            AttributesDescriptor("Names//Party", DamlSyntaxHighlighter.PARTY_NAME),
            AttributesDescriptor("Names//Constructor", DamlSyntaxHighlighter.CONSTRUCTOR),
            AttributesDescriptor("Literals//String", DamlSyntaxHighlighter.STRING),
            AttributesDescriptor("Literals//Number", DamlSyntaxHighlighter.NUMBER),
            AttributesDescriptor("Literals//Boolean", DamlSyntaxHighlighter.BOOLEAN),
            AttributesDescriptor("Literals//Predefined value", DamlSyntaxHighlighter.PREDEFINED_VALUE),
            AttributesDescriptor("Literals//This reference", DamlSyntaxHighlighter.THIS_REFERENCE),
            AttributesDescriptor("Operators//General", DamlSyntaxHighlighter.OPERATOR),
            AttributesDescriptor("Operators//Type/signature", DamlSyntaxHighlighter.TYPE_OPERATOR),
            AttributesDescriptor("Operators//Dot", DamlSyntaxHighlighter.DOT),
            AttributesDescriptor("Punctuation//Parentheses", DamlSyntaxHighlighter.PARENS),
            AttributesDescriptor("Punctuation//Braces", DamlSyntaxHighlighter.BRACES),
            AttributesDescriptor("Punctuation//Brackets", DamlSyntaxHighlighter.BRACKETS),
            AttributesDescriptor("Punctuation//Comma", DamlSyntaxHighlighter.COMMA),
            AttributesDescriptor("Punctuation//Semicolon", DamlSyntaxHighlighter.SEMICOLON)
        )

        private val TAGS = mapOf(
            "module" to DamlSyntaxHighlighter.MODULE_NAME,
            "decl" to DamlSyntaxHighlighter.DECLARATION_NAME,
            "choiceName" to DamlSyntaxHighlighter.CHOICE_NAME,
            "field" to DamlSyntaxHighlighter.FIELD_NAME,
            "type" to DamlSyntaxHighlighter.TYPE_NAME,
            "preludeType" to DamlSyntaxHighlighter.PRELUDE_TYPE,
            "typeParam" to DamlSyntaxHighlighter.TYPE_PARAMETER,
            "importSymbol" to DamlSyntaxHighlighter.IMPORT_SYMBOL,
            "scriptName" to DamlSyntaxHighlighter.SCRIPT_DECLARATION,
            "abstractMethod" to DamlSyntaxHighlighter.ABSTRACT_METHOD,
            "builtin" to DamlSyntaxHighlighter.BUILTIN,
            "party" to DamlSyntaxHighlighter.PARTY_NAME,
            "constructor" to DamlSyntaxHighlighter.CONSTRUCTOR,
            "predefined" to DamlSyntaxHighlighter.PREDEFINED_VALUE,
            "thisRef" to DamlSyntaxHighlighter.THIS_REFERENCE
        )

        private val DEMO_TEXT = """
{-# LANGUAGE DamlSyntax #-}
-- | Deposit workflow demo
module <module>Vault.Deposit.Test</module> where

import <module>Daml.Script</module>
import <module>Vault.Deposit.Processor</module> (<importSymbol>IProcessor</importSymbol>)

data <decl>Receipt</decl> <typeParam>a</typeParam> = <decl>Receipt</decl>
  with
    <field>owner</field> : <preludeType>Party</preludeType>
    <field>payload</field> : <typeParam>a</typeParam>
    <field>note</field> : <preludeType>Optional</preludeType> <preludeType>Text</preludeType>
  deriving (Eq, Show)

template <decl>Deposit</decl>
  with
    <field>operator</field> : <preludeType>Party</preludeType>
    <field>processor</field> : <type>IProcessor</type>
  where
    signatory <field>operator</field>
    observer <field>operator</field>
    ensure True

    <abstractMethod>validateDeposit</abstractMethod> : <preludeType>Party</preludeType> -> Update ()

    nonconsuming choice <choiceName>RunDeposit</choiceName> : ()
      with
        <field>amount</field> : <preludeType>Decimal</preludeType>
      controller <field>operator</field>
      do
        <abstractMethod>validateDeposit</abstractMethod> <thisRef>this</thisRef>.operator
        <builtin>assert</builtin> (amount > 0.0)
        <builtin>pure</builtin> (<constructor>Some</constructor> <predefined>()</predefined>)

<scriptName>setup</scriptName> : <preludeType>Script</preludeType> ()
<scriptName>setup</scriptName> = script do
  <party>alice</party> <- <builtin>allocateParty</builtin> "Alice"
  <builtin>debug</builtin> "[Test] OK"
  _ <- <builtin>createUser</builtin> (<preludeType>User</preludeType> userId (<constructor>Some</constructor> <party>alice</party>)) [<constructor>CanActAs</constructor> <party>alice</party>]
  let due = <builtin>time</builtin> (<builtin>date</builtin> 2026 Jan 1) 10 00 00
  _ <- <builtin>submit</builtin> <party>alice</party> do
    <builtin>createCmd</builtin> <type>Deposit</type> with operator = alice; processor = processor
  <builtin>pure</builtin> <predefined>[]</predefined>
""".trimIndent()
    }
}
