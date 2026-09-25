package com.moonsonglabs.daml.navigation

import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.vfs.JarFileSystem
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.LocalSearchScope
import com.intellij.psi.search.searches.DefinitionsScopedSearch
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.moonsonglabs.daml.DamlFileType
import com.moonsonglabs.daml.lang.DamlNamedElement
import com.moonsonglabs.daml.lang.DamlSourceModel
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class DamlNavigationRegressionTest : BasePlatformTestCase() {
    fun testImplementationsHonorFileAndElementScopesAndExcludeHiddenSources() {
        val api = myFixture.addFileToProject("Api.daml", "module Api where\ninterface Policy where\n  viewtype ()\n  approve : Int -> Int\n")
        val first = myFixture.addFileToProject("First.daml", implementation("First"))
        myFixture.addFileToProject("Second.daml", implementation("Second"))
        myFixture.addFileToProject(".cache/Hidden.daml", implementation("Hidden"))
        val policy = DamlNamedElement.at(api, api.text.indexOf(POLICY))!!
        val method = DamlNamedElement.at(api, api.text.indexOf(METHOD))!!
        val expectedTemplate = DamlNamedElement.at(first, first.text.indexOf(TEMPLATE))!!
        val expectedMethod = DamlNamedElement.at(first, first.text.indexOf(METHOD))!!

        assertEquals(listOf(expectedTemplate), DefinitionsScopedSearch.search(policy, LocalSearchScope(first)).findAll().toList())
        assertEquals(listOf(expectedMethod), DefinitionsScopedSearch.search(method, LocalSearchScope(first)).findAll().toList())
        assertEquals(listOf(expectedMethod), DefinitionsScopedSearch.search(method, LocalSearchScope(expectedMethod)).findAll().toList())
        assertEmpty(DefinitionsScopedSearch.search(policy, LocalSearchScope(expectedMethod)).findAll())
        assertEquals(2, DefinitionsScopedSearch.search(policy, GlobalSearchScope.allScope(project)).findAll().size)
    }

    fun testNavigationWithinDarKeepsArchiveIdentityAndRejectsLooseProjectSources() {
        val archive = Files.createTempFile("navigation-dependency-", ".dar")
        try {
            ZipOutputStream(Files.newOutputStream(archive)).use { zip ->
                mapOf(
                    "src/External.daml" to "module External where\nimport Helper\nimport Outside\nanswer = helperValue\nmissing = looseValue\n",
                    "src/Helper.daml" to "module Helper where\nhelperValue = 42\n"
                ).forEach { (path, text) ->
                    zip.putNextEntry(ZipEntry(path))
                    zip.write(text.toByteArray())
                    zip.closeEntry()
                }
            }
            val darFile = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(archive)!!
            val archiveRoot = JarFileSystem.getInstance().findFileByPath(darFile.path + "!/")!!
            myFixture.addFileToProject("consumer/daml.yaml", "name: consumer\nsource: daml\ndata-dependencies:\n  - '$archive'\n")
            val caller = myFixture.addFileToProject("consumer/daml/Main.daml", "module Main where\nimport External\nmain = answer\n")
            myFixture.addFileToProject("Helper.daml", "module Helper where\nhelperValue = 0\n")
            myFixture.addFileToProject("Outside.daml", "module Outside where\nlooseValue = 0\n")
            val resolver = DamlModuleResolver.getInstance(project)
            val external = resolver.resolveAll(DamlModuleResolver.referenceAt(caller, caller.text.lastIndexOf(ANSWER))!!, caller).single().containingFile
            val helper = resolver.resolveAll(DamlModuleResolver.referenceAt(external, external.text.lastIndexOf(HELPER_VALUE))!!, external).single()
            val sources = project.getService(DamlPackageSources::class.java)

            assertEquals(archiveRoot, sources.containingArchive(helper.containingFile.virtualFile))
            assertFalse(helper.isWritable)
            assertEmpty(resolver.resolveAll(DamlModuleResolver.referenceAt(external, external.text.lastIndexOf(LOOSE_VALUE))!!, external))
        } finally {
            Files.deleteIfExists(archive)
        }
    }

    fun testTypeApplicationMarkerNavigationSkipsWhitespace() {
        val file = myFixture.configureByText(DamlFileType, "module Main where\ninterface Thing where\n  viewtype ()\nvalue cid = toInterfaceContractId @   Thing cid\n")
        val marker = file.text.indexOf('@')
        val typeOffset = file.text.lastIndexOf(TYPE_NAME)
        val expected = DamlNamedElement.at(file, file.text.indexOf(TYPE_NAME))!!
        for (offset in listOf(marker, typeOffset)) {
            val element = file.findElementAt(offset)!!
            assertEquals(expected, DamlGotoDeclarationHandler().getGotoDeclarationTargets(element, offset, myFixture.editor).orEmpty().single())
            assertEquals(expected, DamlDirectNavigationProvider().getNavigationElement(element))
        }
    }

    fun testChoiceTargetQueriesReuseCurrentModelAndObserveEdits() {
        val text = buildString {
            append("module Main where\ntemplate T\n  with owner : Party\n  where\n    signatory owner\n    choice Accept : ()\n      controller owner\n      do pure ()\n")
            repeat(100) { append("function$it value = value\n") }
        }
        val file = myFixture.configureByText(DamlFileType, text)
        val model = DamlSourceModel.get(file)
        val ordinary = file.findElementAt(file.text.lastIndexOf("value"))!!
        repeat(10) {
            assertNull(DamlChoiceUsageTargets.fromElement(ordinary))
            assertSame(model, DamlSourceModel.get(file))
        }
        val choiceOffset = text.indexOf(OLD_CHOICE)
        assertEquals(OLD_CHOICE, DamlChoiceUsageTargets.fromFileOffset(file, choiceOffset)?.name)
        WriteCommandAction.runWriteCommandAction(project) {
            myFixture.editor.document.replaceString(choiceOffset, choiceOffset + OLD_CHOICE.length, NEW_CHOICE)
        }
        PsiDocumentManager.getInstance(project).commitAllDocuments()
        assertNotSame(model, DamlSourceModel.get(file))
        assertEquals(NEW_CHOICE, DamlChoiceUsageTargets.fromFileOffset(file, choiceOffset)?.name)
    }

    private fun implementation(moduleName: String) = "module $moduleName where\nimport Api\ntemplate T\n  with owner : Party\n  where\n    signatory owner\n    interface instance Policy for T where\n      view = ()\n      approve value = value\n"

    companion object {
        private const val POLICY = "Policy"
        private const val METHOD = "approve"
        private const val TEMPLATE = "T"
        private const val ANSWER = "answer"
        private const val HELPER_VALUE = "helperValue"
        private const val LOOSE_VALUE = "looseValue"
        private const val TYPE_NAME = "Thing"
        private const val OLD_CHOICE = "Accept"
        private const val NEW_CHOICE = "Review"
    }
}
