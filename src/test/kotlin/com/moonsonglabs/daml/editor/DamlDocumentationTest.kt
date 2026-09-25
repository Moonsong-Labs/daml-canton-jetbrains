package com.moonsonglabs.daml.editor

import com.intellij.lang.documentation.ide.IdeDocumentationTargetProvider
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.platform.backend.documentation.DocumentationTargetProvider
import com.intellij.psi.PsiDocumentManager
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.moonsonglabs.daml.DamlFileType

class DamlDocumentationTest : BasePlatformTestCase() {
    private val provider = DamlDocumentationTargetProvider()

    private fun hover(text: String): String {
        val file = myFixture.configureByText(DamlFileType, text.trimIndent())
        return provider.documentationTargets(file, myFixture.caretOffset).single().computeDocumentationHint()!!
    }

    fun testTemplateDocsInputsAndCreationResult() {
        val html = hover("""
            module Main where
            -- | A documented agreement.
            -- Signed by its issuer.
            template <caret>Iou
              with
                -- | The issuing party.
                issuer : Party
                amount : Decimal -- ^ Face value.
              where
                signatory issuer
        """)
        assertContains(html, "template Iou", "A documented agreement.", "Signed by its issuer.", "Inputs", "issuer : Party", "The issuing party.", "amount : Decimal", "Face value.", "Creation result", "ContractId Iou")
        assertFalse(html.contains("Iou :"))
    }

    fun testImportedChoiceDocsAndMultilineReturn() {
        myFixture.addFileToProject("Lib.daml", """
            module Lib where
            template Iou
              with
                issuer : Party
              where
                signatory issuer
                {-| Transfer this agreement.
                    Preserves the issuer.
                -}
                nonconsuming choice Transfer :
                  Update (ContractId Iou)
                  with
                    -- | Party receiving the agreement.
                    newOwner : Party
                  controller issuer
                  do abort "example"
        """.trimIndent())
        val html = hover("""
            module Main where
            import Lib
            use = <caret>Transfer
        """)
        assertContains(html, "choice Transfer", "Transfer this agreement.", "Preserves the issuer.", "Owner: Iou", "nonconsuming choice", "newOwner : Party", "Party receiving the agreement.", "Returns", "Update (ContractId Iou)", "Lib")
        assertFalse(html.contains("issuer : Party"))
        assertFalse(html.contains("abort"))
    }

    fun testFunctionInputsHigherOrderConstraintsAndMultilineTypes() {
        val html = hover("""
            module Main where
            -- | Apply a mapping.
            apply : Eq a =>
              (a -> b) ->
              a -> b
            apply mapper value = mapper value
            use = <caret>apply
        """)
        assertContains(html, "Apply a mapping.", "mapper : (a -&gt; b)", "value : a", "Returns</h3><p><code>b</code>")
        assertFalse(html.contains("Argument 3"))
    }

    fun testDefinitionAliasShowsSignatureDocumentation() {
        assertContains(hover("""
            module Main where
            -- | Increment a value.
            bump : Int -> Int
            <caret>bump value = value + 1
        """), "Increment a value.", "value : Int", "Returns")
    }

    fun testInterfaceMethodAndFields() {
        assertContains(hover("""
            module Main where
            interface Asset where
              viewtype ()
              -- | Compute a quote.
              <caret>quote : Decimal -> Decimal
        """), "Compute a quote.", "Owner: Asset", "Argument 1 : Decimal", "Returns")
    }

    fun testZeroInputChoiceAndUnitReturn() {
        assertContains(hover("""
            module Main where
            template Iou
              with issuer : Party
              where
                signatory issuer
                choice <caret>Close : ()
                  controller issuer
                  do pure ()
        """), "Inputs</h3><p>None</p>", "Returns</h3><p><code>()</code>")
    }

    fun testPatternArgumentsAreNotMislabelled() {
        val html = hover("""
            module Main where
            <caret>first : (Int, Int) -> Int
            first (a, b) = a
        """)
        assertContains(html, "Argument 1 : (Int, Int)")
        assertFalse(html.contains("a : (Int"))
    }

    fun testHtmlIsEscapedAndPriorTrailingDocsDoNotLeak() {
        val html = hover("""
            module Main where
            previous : Int -- ^ Previous documentation.
            previous = 1
            -- | Compare <script>alert('x')</script> & values.
            <caret>compareValues : Int -> Bool
            compareValues x = x > 0
        """)
        assertContains(html, "&lt;script&gt;", "&amp; values.")
        assertFalse(html.contains("<script>"))
        assertFalse(html.contains("Previous documentation"))
    }

    fun testUnknownInferredCommentsAndStringsFallThrough() {
        for (body in listOf("<caret>missing", "inferred = 1\nuse = <caret>inferred", "-- <caret>known", "text = \"<caret>known\"")) {
            val file = myFixture.configureByText(DamlFileType, "module Main where\nknown : Int\nknown = 1\n$body\n")
            assertEmpty(provider.documentationTargets(file, myFixture.caretOffset))
        }
    }

    fun testAmbiguousImportsDoNotShowArbitraryDocs() {
        for (module in listOf("One", "Two")) myFixture.addFileToProject("$module.daml", "module $module where\n-- | $module doc.\nvalue : Int\nvalue = 1\n")
        val file = myFixture.configureByText(DamlFileType, "module Main where\nimport One\nimport Two\nuse = <caret>value\n")
        assertEmpty(provider.documentationTargets(file, myFixture.caretOffset))
    }

    fun testModernHoverRegistrationAndPointerRefresh() {
        val file = myFixture.configureByText(DamlFileType, "module Main where\n-- | Old documentation.\nvalue : Int\nvalue = 1\nuse = <caret>value\n")
        val registered = DocumentationTargetProvider.EP_NAME.extensionList.filterIsInstance<DamlDocumentationTargetProvider>().single()
        assertNotNull(registered)
        val target = IdeDocumentationTargetProvider.getInstance(project)
            .documentationTargets(myFixture.editor, file, myFixture.caretOffset).first()
        assertTrue(target is DamlDocumentationTarget)
        assertNotNull(target.computeDocumentation())
        val pointer = target.createPointer()
        WriteCommandAction.runWriteCommandAction(project) {
            val doc = myFixture.editor.document
            val start = doc.text.indexOf("Old documentation.")
            doc.replaceString(start, start + "Old documentation.".length, "Fresh documentation.")
            PsiDocumentManager.getInstance(project).commitDocument(doc)
        }
        assertContains(pointer.dereference()!!.computeDocumentationHint()!!, "Fresh documentation.")
        val element = file.findElementAt(file.text.lastIndexOf("value"))!!
        assertContains(DamlDocumentationProvider().generateDoc(element, element)!!, "Fresh documentation.")
    }

    fun testOrdinaryCommentsAndExplicitForall() {
        assertContains(hover("""
            module Main where
            -- Preserve a value.
            <caret>identity : forall a. a -> a
            identity value = value
        """), "Preserve a value.", "value : a", "Returns</h3><p><code>a</code>")
    }

    fun testExpressionAnnotationsDoNotBecomeFunctionTypes() {
        val file = myFixture.configureByText(DamlFileType, "module Main where\nuse n = n + (1 : Int)\nvalue = <caret>use 2\n")
        assertEmpty(provider.documentationTargets(file, myFixture.caretOffset))
    }

    private fun assertContains(html: String, vararg fragments: String) {
        for (fragment in fragments) assertTrue("Missing '$fragment' in $html", html.contains(fragment))
    }
}
