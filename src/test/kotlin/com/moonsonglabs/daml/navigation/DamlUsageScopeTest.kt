package com.moonsonglabs.daml.navigation

import com.intellij.psi.PsiFile
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.LocalSearchScope
import com.intellij.psi.search.SearchScope
import com.intellij.psi.search.searches.ReferencesSearch
import com.intellij.refactoring.rename.RenameProcessor
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.moonsonglabs.daml.lang.DamlNamedElement

class DamlUsageScopeTest : BasePlatformTestCase() {
    fun testFunctionUsagesExcludeHiddenAndGeneratedFilesEvenInAllScope() {
        val source = myFixture.addFileToProject("Lib.daml", "module Lib where\nbump n = n + 1\n")
        val visible = myFixture.addFileToProject("src/Main.daml", usage())
        val excluded = excludedFiles()
        val target = DamlNamedElement.at(source, source.text.indexOf(NAME))!!
        assertEquals(setOf(visible.virtualFile), usages(target, GlobalSearchScope.allScope(project)))
        for (file in excluded) assertEmpty(usages(target, LocalSearchScope(file)))
        assertEquals(setOf(visible.virtualFile), usages(target, LocalSearchScope(visible)))
    }

    fun testChoiceUsagesExcludeHiddenCopiesAndKeepQualifiedCalls() {
        val source = myFixture.addFileToProject("Lib.daml", "module Lib where\ntemplate T\n  with owner : Party\n  where\n    signatory owner\n    choice Accept : ()\n      controller owner\n      do pure ()\n")
        val content = "module Main where\nimport qualified Lib as L\nmain cid = exercise cid (L.Accept)\n"
        val visible = myFixture.addFileToProject("tests/Main.daml", content)
        for (path in EXCLUDED_PATHS) myFixture.addFileToProject(path, content)
        val target = DamlNamedElement.at(source, source.text.indexOf("Accept"))!!
        assertEquals(setOf(visible.virtualFile), usages(target, GlobalSearchScope.allScope(project)))
    }

    fun testRenameLeavesHiddenAndGeneratedFilesUntouched() {
        val source = myFixture.addFileToProject("Lib.daml", "module Lib where\nbump n = n + 1\n")
        val visible = myFixture.addFileToProject("src/Main.daml", usage())
        val excluded = excludedFiles()
        val before = excluded.associateWith { it.text }
        val target = DamlNamedElement.at(source, source.text.indexOf(NAME))!!
        RenameProcessor(project, target, "increment", false, false).run()
        assertTrue(visible.text.contains("increment 2"))
        before.forEach { (file, text) -> assertEquals(file.virtualFile.path, text, file.text) }
    }

    fun testHiddenReceiverCopiesDoNotBlockFieldRenamePreflight() {
        val source = myFixture.addFileToProject("Lib.daml", "module Lib where\ndata Person = Person with\n  age : Int\n")
        myFixture.addFileToProject(".cache/Generated.daml", "module Generated where\nreadAge p = p.age\n")
        val target = DamlNamedElement.at(source, source.text.indexOf("age"))!!
        DamlRenameProcessor().prepareRenaming(target, "years", mutableMapOf())
    }

    fun testHiddenDirectoryIsExcludedEvenIfRegisteredAsContentRoot() {
        val file = myFixture.addFileToProject(".daml/Registered.daml", usage())
        com.intellij.testFramework.PsiTestUtil.addContentRoot(module, file.virtualFile.parent)
        assertFalse(DamlUsageScope.accepts(project, file.virtualFile))
    }

    private fun usages(target: DamlNamedElement, scope: SearchScope) =
        ReferencesSearch.search(target, scope, true).findAll().map { it.element.containingFile.virtualFile }.toSet()
    private fun usage() = "module Main where\nimport Lib\nmain = bump 2\n-- bump\nlabel = \"bump\"\n"
    private fun excludedFiles(): List<PsiFile> = EXCLUDED_PATHS.map { myFixture.addFileToProject(it, usage()) }
    companion object {
        private const val NAME = "bump"
        private val EXCLUDED_PATHS = listOf(
            ".daml/package-database/pkg/Main.daml", "nested/.daml/generated/Main.daml",
            ".cache/Main.daml", "nested/.hidden/Main.daml", ".Main.daml",
            "build/Main.daml", "out/Main.daml", "node_modules/pkg/Main.daml"
        )
    }
}
