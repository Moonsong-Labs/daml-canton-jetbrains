package com.moonsonglabs.daml.syntax

import com.intellij.openapi.command.WriteCommandAction
import com.intellij.psi.PsiDocumentManager
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.moonsonglabs.daml.DamlFileType

class DamlFunctionHighlightingTest : BasePlatformTestCase() {
    override fun setUp() {
        super.setUp()
        myFixture.addFileToProject(LIBRARY_PATH, LIBRARY_SOURCE)
    }

    fun testImportedFunctionInWithdrawalRecordAndDirectCall() {
        configure("""
            import FundedSwaps
            recover request = Recovery with
              withdrawals = map withdrawal [request.inputAllocation, request.outputAllocation]
            direct = withdrawal 1
        """)
        assertFunctionCalls("withdrawal", "withdrawal")
    }

    fun testQualifiedAliasAndExplicitImport() {
        configure("""
            import qualified FundedSwaps as F
            import FundedSwaps (withdrawal)
            direct = F.withdrawal 1
            mapped = map withdrawal [2]
        """)
        assertFunctionCalls("withdrawal", "withdrawal")
    }

    fun testLocalVariablesFieldsConstantsAndContainersKeepTheirColors() {
        configure("""
            import FundedSwaps
            shadow withdrawal = withdrawal
            field request = request.withdrawal
            constant = count
            container = callbacks
            monadic = do
              withdrawal <- pure 1
              pure withdrawal
        """)
        assertFunctionCalls()
    }

    fun testHidingAndQualifiedOnlyImportsAreRespected() {
        configure("""
            import FundedSwaps hiding (withdrawal)
            import qualified FundedSwaps as F
            hidden = withdrawal 1
            visible = F.withdrawal 2
        """)
        assertFunctionCalls("withdrawal")
    }

    fun testInlineRecordLabelDoesNotBecomeAFunctionCall() {
        configure("""
            import FundedSwaps
            recover = Recovery with withdrawal = withdrawal 1
        """)
        assertFunctionCalls("withdrawal")
    }

    fun testLocalMultilineFunctionSignatureAndContainerOfFunctions() {
        configure("""
            identity :
              Int -> Int
            identity = \value -> value
            callbacks :
              [Int -> Int]
            callbacks = []
            direct = identity 1
            container = callbacks
        """)
        assertFunctionCalls("identity")
    }

    fun testImportedMultilineFunctionSignatureAndContainerOfFunctions() {
        myFixture.addFileToProject("Multiline.daml", """
            module Multiline where
            identity :
              Int -> Int
            identity = \value -> value
            callbacks :
              [Int -> Int]
            callbacks = []
        """.trimIndent())
        configure("""
            import qualified Multiline as M
            import Multiline (identity, callbacks)
            direct = identity 1
            qualifiedCall = M.identity 2
            container = callbacks
        """)
        assertFunctionCalls("identity", "identity")
    }

    fun testAmbiguousImportIsNotColoredAsAFunction() {
        myFixture.addFileToProject("Other.daml", "module Other where\nwithdrawal = 1\n")
        configure("""
            import FundedSwaps
            import Other
            ambiguous = withdrawal
        """)
        assertFunctionCalls()
    }

    fun testImportedFunctionColorUpdatesAfterItsDefinitionChanges() {
        configure("""
            import FundedSwaps
            result = withdrawal
        """)
        assertFunctionCalls("withdrawal")
        val library = myFixture.findFileInTempDir(LIBRARY_PATH)
        val manager = PsiDocumentManager.getInstance(project)
        val file = com.intellij.psi.PsiManager.getInstance(project).findFile(library)!!
        val document = manager.getDocument(file)!!
        WriteCommandAction.runWriteCommandAction(project) {
            document.setText("module FundedSwaps where\nwithdrawal : Int\nwithdrawal = 1\n")
            manager.commitDocument(document)
        }
        assertFunctionCalls()
    }

    fun testColorSettingsExposeFunctionCallsWithVisibleDefaults() {
        val page = DamlColorSettingsPage()
        assertTrue(page.attributeDescriptors.any { it.key == DamlSyntaxHighlighter.FUNCTION_CALL })
        assertTrue(page.additionalHighlightingTagToDescriptorMap.values.contains(DamlSyntaxHighlighter.FUNCTION_CALL))
        assertSame(com.intellij.openapi.editor.DefaultLanguageHighlighterColors.FUNCTION_DECLARATION,
            DamlSyntaxHighlighter.FUNCTION_CALL.fallbackAttributeKey)
        val manager = com.intellij.openapi.editor.colors.EditorColorsManager.getInstance()
        for (name in listOf("Default", "Darcula")) {
            val scheme = manager.getScheme(name)
            assertNotNull("Base color scheme $name must be available", scheme)
            val foreground = scheme!!.getAttributes(DamlSyntaxHighlighter.FUNCTION_CALL).foregroundColor
            assertNotNull(foreground)
            assertFalse("Function calls must be distinct from plain identifiers in $name", foreground == scheme.defaultForeground)
        }
    }

    private fun configure(source: String) {
        myFixture.configureByText(DamlFileType, "module Main where\n" + source.trimIndent() + "\n")
    }

    private fun assertFunctionCalls(vararg expected: String) {
        val text = myFixture.file.text
        val actual = myFixture.doHighlighting().filter {
            it.forcedTextAttributesKey == DamlSyntaxHighlighter.FUNCTION_CALL
        }.sortedBy { it.startOffset }.map { text.substring(it.startOffset, it.endOffset) }
        assertEquals(expected.toList(), actual)
    }

    companion object {
        private const val LIBRARY_PATH = "FundedSwaps.daml"
        private val LIBRARY_SOURCE = """
            module FundedSwaps where
            withdrawal : Int -> Int
            withdrawal amount = amount
            count : Int
            count = 1
            callbacks : [Int -> Int]
            callbacks = []
        """.trimIndent()
    }
}
