package com.moonsonglabs.daml.syntax

import com.intellij.psi.tree.IElementType
import com.moonsonglabs.daml.DamlLexer
import com.moonsonglabs.daml.DamlTokenTypes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DamlLexerTest {
    @Test
    fun `classifies DAML keyword groups and predefined literals`() {
        val tokens = lex(
            """
module Main where
import qualified Daml.Script as Script
template Deposit
  with operator : Party
  where
    nonconsuming choice RunDeposit : ()
      controller operator
      do
        assert True
        pure []
""".trimIndent()
        )

        assertHas(tokens, "module", DamlTokenTypes.MODULE_KEYWORD)
        assertHas(tokens, "import", DamlTokenTypes.IMPORT_KEYWORD)
        assertHas(tokens, "qualified", DamlTokenTypes.IMPORT_KEYWORD)
        assertHas(tokens, "template", DamlTokenTypes.DECLARATION_KEYWORD)
        assertHas(tokens, "with", DamlTokenTypes.DAML_KEYWORD)
        assertHas(tokens, "nonconsuming", DamlTokenTypes.CHOICE_MODIFIER_KEYWORD)
        assertHas(tokens, "do", DamlTokenTypes.CONTROL_KEYWORD)
        assertHas(tokens, "assert", DamlTokenTypes.BUILTIN_IDENTIFIER)
        assertHas(tokens, "pure", DamlTokenTypes.BUILTIN_IDENTIFIER)
        assertHas(tokens, "Party", DamlTokenTypes.PRELUDE_TYPE)
        assertHas(tokens, "True", DamlTokenTypes.BOOLEAN_LITERAL)
        assertHas(tokens, "()", DamlTokenTypes.UNIT_LITERAL)
        assertHas(tokens, "[]", DamlTokenTypes.EMPTY_LIST_LITERAL)
    }

    @Test
    fun `classifies native type after multiline template with clause`() {
        val tokens = lex(
            """
template VaultFactory
  with
    vaultIssuer : Party
  where
    signatory vaultIssuer
""".trimIndent()
        )

        assertHas(tokens, "VaultFactory", DamlTokenTypes.TYPE_NAME)
        assertHas(tokens, "vaultIssuer", DamlTokenTypes.IDENTIFIER)
        assertHas(tokens, "Party", DamlTokenTypes.PRELUDE_TYPE)
    }

    @Test
    fun `classifies script and interface helpers as builtins`() {
        val tokens = lex(
            """
cid <- submit operator ${'$'} createCmd T with owner
debug "[Test] OK"
assertMsg "ok" True
tree <- submitTree operator ${'$'} exerciseCmd cid Archive
parties <- listKnownParties
user <- getUser =<< validateUserId "Alice"
intCid = toInterfaceContractId @IValidator cid
""".trimIndent()
        )

        assertHas(tokens, "submit", DamlTokenTypes.BUILTIN_IDENTIFIER)
        assertHas(tokens, "createCmd", DamlTokenTypes.BUILTIN_IDENTIFIER)
        assertHas(tokens, "debug", DamlTokenTypes.BUILTIN_IDENTIFIER)
        assertHas(tokens, "assertMsg", DamlTokenTypes.BUILTIN_IDENTIFIER)
        assertHas(tokens, "submitTree", DamlTokenTypes.BUILTIN_IDENTIFIER)
        assertHas(tokens, "exerciseCmd", DamlTokenTypes.BUILTIN_IDENTIFIER)
        assertHas(tokens, "listKnownParties", DamlTokenTypes.BUILTIN_IDENTIFIER)
        assertHas(tokens, "getUser", DamlTokenTypes.BUILTIN_IDENTIFIER)
        assertHas(tokens, "validateUserId", DamlTokenTypes.BUILTIN_IDENTIFIER)
        assertHas(tokens, "toInterfaceContractId", DamlTokenTypes.BUILTIN_IDENTIFIER)
    }

    @Test
    fun `classifies Prelude types constructors and DAML clauses`() {
        val tokens = lex(
            """
message : Optional Text
message = Some "ok"
fallback = None
cid : ContractId T
amount : Decimal
party : Party
when : Time
day : Date
action : Update ()
scriptValue : Script ()
exception E with message : Text
template T with p : Party where magreement "legacy"
partyHint : PartyIdHint
user : User
rights : [UserRight]
""".trimIndent()
        )

        listOf(
            "Optional", "Text", "ContractId", "Decimal", "Party", "Time", "Date",
            "Update", "Script", "PartyIdHint", "User", "UserRight"
        )
            .forEach { assertHas(tokens, it, DamlTokenTypes.PRELUDE_TYPE) }
        assertHas(tokens, "Some", DamlTokenTypes.PREDEFINED_IDENTIFIER)
        assertHas(tokens, "None", DamlTokenTypes.PREDEFINED_IDENTIFIER)
        assertHas(tokens, "message", DamlTokenTypes.DAML_KEYWORD)
        assertHas(tokens, "magreement", DamlTokenTypes.DAML_KEYWORD)
    }

    @Test
    fun `classifies current date time optional and user management helpers from course corpus`() {
        val tokens = lex(
            """
clinic <- allocatePartyWithHint "Clinic" (PartyIdHint "clinic")
now = time (date 2024 Mar 05) 10 00 00
laterDays = wholeDays (subTime now now)
assertMsg "date taken" (isNone (Some now))
createUser (User userId (Some clinic)) [CanActAs clinic, CanReadAs clinic, CanReadAsAnyParty]
""".trimIndent()
        )

        listOf("allocatePartyWithHint", "date", "time", "wholeDays", "subTime", "isNone", "createUser")
            .forEach { assertHas(tokens, it, DamlTokenTypes.BUILTIN_IDENTIFIER) }
        listOf("PartyIdHint", "User")
            .forEach { assertHas(tokens, it, DamlTokenTypes.PRELUDE_TYPE) }
        listOf("Mar", "Some", "CanActAs", "CanReadAs", "CanReadAsAnyParty")
            .forEach { assertHas(tokens, it, DamlTokenTypes.PREDEFINED_IDENTIFIER) }
    }

    @Test
    fun `classifies common type and update operators`() {
        val tokens = lex("f : A -> Update (); g :: a => b; x <- pure (); y == z; a /= b; r.field = 1")

        assertHas(tokens, ":", DamlTokenTypes.COLON)
        assertHas(tokens, "->", DamlTokenTypes.ARROW)
        assertHas(tokens, "::", DamlTokenTypes.DOUBLE_COLON)
        assertHas(tokens, "=>", DamlTokenTypes.BIG_ARROW)
        assertHas(tokens, "<-", DamlTokenTypes.BIND_ARROW)
        assertEquals(2, tokens.count { it.text == "()" && it.type == DamlTokenTypes.UNIT_LITERAL })
        assertEquals(2, tokens.count { it.type == DamlTokenTypes.EQUALITY_OPERATOR })
        assertHas(tokens, ".", DamlTokenTypes.DOT)
        assertHas(tokens, "=", DamlTokenTypes.EQUALS)
    }

    private fun lex(text: String): List<Token> {
        val lexer = DamlLexer()
        lexer.start(text)
        val tokens = mutableListOf<Token>()
        while (lexer.tokenType != null) {
            val type = lexer.tokenType!!
            if (type != DamlTokenTypes.WHITE_SPACE) {
                tokens += Token(text.substring(lexer.tokenStart, lexer.tokenEnd), type)
            }
            lexer.advance()
        }
        return tokens
    }

    private fun assertHas(tokens: List<Token>, text: String, type: IElementType) {
        assertTrue("Expected token $text as $type in $tokens", tokens.any { it.text == text && it.type == type })
    }

    private data class Token(val text: String, val type: IElementType)
}
