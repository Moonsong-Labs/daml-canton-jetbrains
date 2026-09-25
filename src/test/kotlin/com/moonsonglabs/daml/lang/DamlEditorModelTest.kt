package com.moonsonglabs.daml.lang

import com.intellij.psi.PsiReferenceService
import com.intellij.psi.search.searches.ReferencesSearch
import com.intellij.refactoring.rename.RenameProcessor
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.moonsonglabs.daml.DamlFileType
import com.moonsonglabs.daml.DamlTokenTypes
import com.moonsonglabs.daml.navigation.DamlModuleResolver
import com.moonsonglabs.daml.navigation.DamlChoiceNames
import com.moonsonglabs.daml.syntax.DamlHighlightingClassifier

class DamlEditorModelTest : BasePlatformTestCase() {
    fun testParserKeepsImportsAndFunctionsOutsideModuleHeading() {
        val file = myFixture.configureByText(DamlFileType, "module Main where\nimport Other\nfirst x = x\nsecond = 2\n")
        assertEquals(DamlTokenTypes.MODULE_DECL, file.firstChild.node.elementType)
        assertFalse(file.firstChild.text.contains("first"))
        assertNotNull(DamlNamedElement.at(file, file.text.indexOf("first")))
        assertNotNull(DamlNamedElement.at(file, file.text.indexOf("second")))
    }
    fun testDoBindingsDoNotLeakAcrossFunctionsAndParametersResolve() {
        val text = "module Main where\nfirst a = do\n  x <- pure a\n  pure x\nsecond = x\n"
        val file = myFixture.configureByText(DamlFileType, text)
        fun resolve(offset: Int) = DamlModuleResolver.getInstance(project).resolveAll(DamlModuleResolver.referenceAt(file, offset)!!, file).singleOrNull()
        assertEquals(text.indexOf("a ="), resolve(text.indexOf("a\n"))?.textOffset)
        assertEquals(text.indexOf("x <-"), resolve(text.indexOf("x\n"))?.textOffset)
        assertNull(resolve(text.lastIndexOf("x")))
    }
    fun testCommentImportsEmptyListsAndHidingAreRespected() {
        myFixture.addFileToProject("Other.daml", "module Other where\nx = 1\ny = 2\n")
        val file = myFixture.configureByText(DamlFileType, "module Main where\n{- import Other -}\nimport Other ()\nmain = x\n")
        val ref = DamlModuleResolver.referenceAt(file, file.text.lastIndexOf("x"))!!
        assertEmpty(DamlModuleResolver.getInstance(project).resolveAll(ref, file))
        val model = DamlSourceModel.parse("module Main where\nimport qualified Other as O hiding\n  (x,\n   y)\nmain = O.x\n")
        assertEquals(1, model.imports.size)
        assertFalse(model.imports.single().exposes("x"))
        assertFalse(model.imports.single().matches("Other"))
        assertTrue(model.imports.single().matches("O"))
    }
    fun testAmbiguousImportsOfferBothTargets() {
        myFixture.addFileToProject("One.daml", "module One where\nx = 1\n")
        myFixture.addFileToProject("Two.daml", "module Two where\nx = 2\n")
        val file = myFixture.configureByText(DamlFileType, "module Main where\nimport One\nimport Two\nmain = x\n")
        val reference = DamlModuleResolver.referenceAt(file, file.text.lastIndexOf("x"))!!
        assertEquals(2, DamlModuleResolver.getInstance(project).resolveAll(reference, file).size)
        assertNull(DamlModuleResolver.getInstance(project).resolveSymbolReference(reference, file.virtualFile))
    }
    fun testUnrelatedPackageDoesNotWinModuleResolution() {
        myFixture.addFileToProject("one/daml.yaml", "name: one\nsource: daml\n")
        myFixture.addFileToProject("one/daml/Lib.daml", "module Lib where\nx = 1\n")
        myFixture.addFileToProject("two/daml.yaml", "name: two\nsource: daml\n")
        val file = myFixture.addFileToProject("two/daml/Main.daml", "module Main where\nimport Lib\nmain = x\n")
        val reference = DamlModuleResolver.referenceAt(file, file.text.lastIndexOf("x"))!!
        assertEmpty(DamlModuleResolver.getInstance(project).resolveAll(reference, file))
    }
    fun testRenameUpdatesSignatureDefinitionImportsAndReferencesOnly() {
        val source = myFixture.addFileToProject("Lib.daml", "module Lib where\nbump : Int -> Int\nbump n = n + 1\n")
        val user = myFixture.addFileToProject("Main.daml", "module Main where\nimport Lib (bump)\nmain = bump 2\n-- bump\ntext = \"bump\"\n")
        val target = DamlNamedElement.at(source, source.text.indexOf("bump"))!!
        val refs = ReferencesSearch.search(target).findAll()
        assertEquals(3, refs.size)
        RenameProcessor(project, target, "increment", false, false).run()
        assertTrue(source.text.contains("increment : Int -> Int\nincrement n"))
        assertTrue(user.text.contains("import Lib (increment)\nmain = increment 2"))
        assertTrue(user.text.contains("-- bump\ntext = \"bump\""))
    }
    fun testRenameParameterDoesNotTouchSameNameInAnotherFunction() {
        val file = myFixture.configureByText(DamlFileType, "module Main where\nfirst x = x + 1\nsecond x = x + 2\n")
        val target = DamlNamedElement.at(file, file.text.indexOf("x ="))!!
        RenameProcessor(project, target, "value", false, false).run()
        assertTrue(file.text.contains("first value = value + 1"))
        assertTrue(file.text.contains("second x = x + 2"))
    }
    fun testLocalFunctionParameterShadowsOuterParameterAndRenamesIndependently() {
        val text = "module Main where\nmain x = do\n  let helper x = x + 1\n  pure (helper x)\n"
        val file = myFixture.configureByText(DamlFileType, text)
        val model = DamlSourceModel.get(file)
        val innerParameter = text.indexOf("x =", text.indexOf("helper"))
        assertEquals(innerParameter, model.local("x", text.indexOf("x +"))?.start)
        assertEquals(text.indexOf("x ="), model.local("x", text.lastIndexOf("x"))?.start)
        val outer = DamlNamedElement.at(file, text.indexOf("x ="))!!
        RenameProcessor(project, outer, "outer", false, false).run()
        assertEquals("module Main where\nmain outer = do\n  let helper x = x + 1\n  pure (helper outer)\n", file.text)
        val inner = DamlNamedElement.at(file, file.text.indexOf("x ="))!!
        RenameProcessor(project, inner, "inner", false, false).run()
        assertEquals("module Main where\nmain outer = do\n  let helper inner = inner + 1\n  pure (helper outer)\n", file.text)
    }

    fun testLocalFunctionParametersOwnMultilineBodyAndEndBeforeNextStatement() {
        val text = "module Main where\nmain = do\n  let helper argument =\n        argument + 1\n  pure argument\n"
        val model = DamlSourceModel.parse(text)
        assertEquals(text.indexOf("argument"), model.local("argument", text.indexOf("argument +"))?.start)
        assertNull(model.local("argument", text.lastIndexOf("argument")))
    }
    fun testCommentsChoiceArgumentsAndStatementBoundaries() {
        val text = "module Main where\n{- choice Fake : () -}\nmain = do\n  exerciseCmd cid (Accept with owner = alice)\n  exerciseCmd cid $ Accept with owner = alice\n  pure Reject\n"
        assertEmpty(DamlChoiceNames.declarations(text))
        assertEquals(listOf("Accept", "Accept"), DamlChoiceNames.uses(text).map { it.name })
    }
    fun testShadowedBuiltinsAndContextualWordsAreOrdinaryBindings() {
        val text = "module Main where\nmessage : Text\nmessage = \"ok\"\npure x = x\nmain = pure 1\n"
        val roles = DamlHighlightingClassifier.classify(DamlSourceModel.parse(text))
        assertEquals(DamlHighlightingClassifier.Role.DECLARATION_NAME, roles[text.indexOf("message")])
        assertEquals(DamlHighlightingClassifier.Role.FUNCTION_CALL, roles[text.lastIndexOf("pure")])
    }
    fun testTripleDashCommentAndTypeConsPattern() {
        val text = "module Main where\n--- ordinary comment\nmain xs = case xs of\n  x :: rest -> rest\n"
        val model = DamlSourceModel.parse(text)
        assertFalse(model.tokens.first { it.text.startsWith("---") }.code)
        val roles = DamlHighlightingClassifier.classify(model)
        assertNotSame(DamlHighlightingClassifier.Role.TYPE_PARAMETER, roles[text.lastIndexOf("rest")])
        assertEquals(text.indexOf("rest"), model.local("rest", text.lastIndexOf("rest"))?.start)
    }
    fun testHighlightCacheDistinguishesHashCollisions() {
        val a = "main = do\n  an <- allocateParty \"x\"\n  pure an\n"
        val b = a.replace("an", "bO")
        assertEquals(a.hashCode(), b.hashCode())
        assertEquals(DamlHighlightingClassifier.Role.PARTY_NAME, DamlHighlightingClassifier.roleAt(a, a.lastIndexOf("an"), "an"))
        assertEquals(DamlHighlightingClassifier.Role.PARTY_NAME, DamlHighlightingClassifier.roleAt(b, b.lastIndexOf("bO"), "bO"))
    }
    fun testModelInvalidatesAfterAnEdit() {
        val file = myFixture.configureByText(DamlFileType, "module Main where\nfirst = 1\n")
        val before = DamlSourceModel.get(file)
        myFixture.editor.caretModel.moveToOffset(file.textLength)
        myFixture.type("second = 2\n")
        com.intellij.psi.PsiDocumentManager.getInstance(project).commitAllDocuments()
        val after = DamlSourceModel.get(file)
        assertNotSame(before, after)
        assertNotNull(after.byName["second"])
    }

    fun testInterfaceAndMethodImplementationsUseResolvedInterfaceIdentity() {
        val api = myFixture.addFileToProject("Api.daml", "module Api where\ninterface Policy where\n  viewtype ()\n  approve : Int -> Int\n")
        val impl = myFixture.addFileToProject("Impl.daml", "module Impl where\nimport Api\ntemplate T\n  with owner : Party\n  where\n    signatory owner\n    interface instance Policy for T where\n      view = ()\n      approve value = value\n")
        val policy = DamlNamedElement.at(api, api.text.indexOf("Policy"))!!
        val method = DamlNamedElement.at(api, api.text.indexOf("approve"))!!
        val templates = com.intellij.psi.search.searches.DefinitionsScopedSearch.search(policy).findAll()
        assertEquals(listOf("T"), templates.map { it.text })
        val methods = com.intellij.psi.search.searches.DefinitionsScopedSearch.search(method).findAll()
        assertEquals(impl.text.indexOf("approve"), methods.single().textOffset)
    }
    fun testExportListsDoNotExportHiddenConstructors() {
        val model = DamlSourceModel.parse("module Types (Secret) where\ndata Secret = Hidden\n")
        assertTrue(model.exported(model.byName["Secret"]!!.first()))
        assertFalse(model.exported(model.byName["Hidden"]!!.first()))
        val public = DamlSourceModel.parse("module Types (Secret(..)) where\ndata Secret = Hidden\n")
        assertTrue(public.exported(public.byName["Hidden"]!!.first()))
    }
    fun testBindingIsNotInScopeInItsOwnMonadicRhs() {
        val text = "module Main where\nmain x = do\n  x <- pure x\n  pure x\n"
        val model = DamlSourceModel.parse(text)
        assertEquals(text.indexOf("x ="), model.local("x", text.indexOf("x\n"))?.start)
        assertEquals(text.indexOf("x <-"), model.local("x", text.lastIndexOf("x"))?.start)
    }
    fun testAnnotationsDocumentationAndFoldingWorkWithoutServer() {
        val file = myFixture.configureByText(DamlFileType, "module Main where\ndata Person = Person with\n  age : Int\n-- | A value.\nperson : Person\nperson = undefined\n")
        val target = DamlNamedElement.at(file, file.text.indexOf("person :"))!!
        val doc = com.moonsonglabs.daml.editor.DamlDocumentationProvider().generateDoc(target, target)!!
        assertTrue(doc.contains("A value."))
        assertTrue(doc.contains("Person"))
        val folds = com.moonsonglabs.daml.editor.DamlFoldingBuilder().buildFoldRegions(file, myFixture.editor.document, false)
        assertTrue(folds.any { it.range.contains(file.text.indexOf("age")) })
    }
    fun testCasePatternBindersStayInTheirArm() {
        val text = "module Main where\nmain xs = case xs of\n  Some value -> value\n  None -> value\n"
        val model = DamlSourceModel.parse(text)
        assertNotNull(model.local("value", text.indexOf("value\n")))
        assertNull(model.local("value", text.lastIndexOf("value")))
    }
    fun testSameFileModelIsReusedAndLargeFilesHaveAllSymbols() {
        val text = buildString { append("module Large where\n"); repeat(4000) { append("function$it value = value + $it\n") } }
        val file = myFixture.configureByText(DamlFileType, text)
        val model = DamlSourceModel.get(file)
        assertSame(model, DamlSourceModel.get(file))
        assertEquals(4000, model.symbols.count { it.kind == DamlSourceModel.Kind.FUNCTION })
        assertEquals(4000, model.symbols.count { it.kind == DamlSourceModel.Kind.PARAMETER })
    }

    fun testRenameDetectsCaptureAtAnImportedUsage() {
        val source = myFixture.addFileToProject("Lib.daml", "module Lib where\nbump n = n + 1\n")
        myFixture.addFileToProject("Main.daml", "module Main where\nimport Lib\nmain increment = bump increment\n")
        val target = DamlNamedElement.at(source, source.text.indexOf("bump"))!!
        val conflicts = com.intellij.util.containers.MultiMap<com.intellij.psi.PsiElement, String>()
        com.moonsonglabs.daml.navigation.DamlRenameProcessor().findExistingNameConflicts(target, "increment", conflicts)
        assertFalse(conflicts.isEmpty)
    }
    fun testUnsafeFieldRenameStopsBeforeEditingWhenCompilerIsUnavailable() {
        val text = "module Main where\ndata Person = Person with\n  age : Int\nreadAge p = p.age\n"
        val file = myFixture.configureByText(DamlFileType, text)
        val field = DamlNamedElement.at(file, text.indexOf("age :"))!!
        try {
            com.moonsonglabs.daml.navigation.DamlRenameProcessor().prepareRenaming(field, "years", mutableMapOf())
            fail("Unresolved receivers must block rename")
        } catch (expected: com.intellij.util.IncorrectOperationException) {
            assertTrue(expected.message!!.contains("before editing"))
        }
        assertEquals(text, file.text)
    }
    fun testSelectedDarSuppliesReadOnlySourceWithVersionIdentity() {
        val archive = java.nio.file.Files.createTempFile("selected-package-1.2.3-", ".dar")
        try {
            java.util.zip.ZipOutputStream(java.nio.file.Files.newOutputStream(archive)).use { zip ->
                zip.putNextEntry(java.util.zip.ZipEntry("src/External.daml"))
                zip.write("module External where\nanswer = 42\n".toByteArray())
                zip.closeEntry()
            }
            val darFile = com.intellij.openapi.vfs.LocalFileSystem.getInstance().refreshAndFindFileByNioFile(archive)
            assertNotNull("DAR VFS", darFile)
            assertNotNull("DAR root", com.intellij.openapi.vfs.JarFileSystem.getInstance().findFileByPath(darFile!!.path + "!/"))
            myFixture.addFileToProject("daml.yaml", "name: consumer\nsource: daml\ndata-dependencies:\n  - '$archive'\n")
            val file = myFixture.addFileToProject("daml/Main.daml", "module Main where\nimport External\nmain = answer\n")
            val sources = project.getService(com.moonsonglabs.daml.navigation.DamlPackageSources::class.java)
            assertNotNull("Config owner", sources.root(file.virtualFile))
            assertEquals("Selected archive", 1, sources.dependencies(file.virtualFile).size)
            assertEquals("Archive module", 1, sources.modulesInArchive(sources.dependencies(file.virtualFile).single(), "External").size)
            val ref = DamlModuleResolver.referenceAt(file, file.text.lastIndexOf("answer"))!!
            val target = DamlModuleResolver.getInstance(project).resolveAll(ref, file).single()
            assertEquals("jar", target.containingFile.virtualFile.fileSystem.protocol)
            assertFalse(target.isWritable)
            assertTrue(target.containingFile.virtualFile.path.contains(archive.fileName.toString()))
        } finally { java.nio.file.Files.deleteIfExists(archive) }
    }

    fun testTypeAndConstructorWithSameSpellingHaveSeparateIdentity() {
        val text = "module Main where\ndata Person = Person with\n  age : Int\nperson : Person\nperson = Person 2\n"
        val file = myFixture.configureByText(DamlFileType, text)
        val typeOffset = text.indexOf("Person", text.indexOf("person :"))
        val valueOffset = text.lastIndexOf("Person")
        val resolver = DamlModuleResolver.getInstance(project)
        assertEquals(text.indexOf("Person"), resolver.resolveAll(DamlModuleResolver.referenceAt(file, typeOffset)!!, file).single().textOffset)
        assertEquals(text.indexOf("Person", text.indexOf("= Person")), resolver.resolveAll(DamlModuleResolver.referenceAt(file, valueOffset)!!, file).single().textOffset)
    }
    fun testLambdaBinderEndsAtItsClosingParenthesis() {
        val text = "module Main where\nmain x = (\\x -> x) x\n"
        val model = DamlSourceModel.parse(text)
        assertEquals(text.indexOf("x ="), model.local("x", text.lastIndexOf("x"))?.start)
        assertEquals(text.indexOf("x ->"), model.local("x", text.indexOf("x)"))?.start)
    }
}
