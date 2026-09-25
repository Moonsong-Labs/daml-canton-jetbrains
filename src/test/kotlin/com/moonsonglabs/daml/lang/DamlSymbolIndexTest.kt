package com.moonsonglabs.daml.lang

import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.io.FileUtil
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.navigation.NavigationItem
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.testFramework.PsiTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.util.Processor
import com.intellij.util.indexing.FileContentImpl
import com.intellij.util.indexing.FindSymbolParameters
import com.moonsonglabs.daml.navigation.DamlModuleResolver
import com.moonsonglabs.daml.navigation.DamlSymbolContributor
import com.moonsonglabs.daml.navigation.DamlUsageScope
import java.nio.file.Files

class DamlSymbolIndexTest : BasePlatformTestCase() {
    fun testGeneratedDirectoryNamesOutsideContentRootDoNotHideSource() {
        val temporary = Files.createTempDirectory("daml-index-roots")
        Disposer.register(testRootDisposable) { FileUtil.delete(temporary.toFile()) }
        for (directoryName in listOf(GENERATED_DIRECTORY, "out")) {
            val contentRoot = Files.createDirectories(temporary.resolve(directoryName))
            val source = Files.writeString(contentRoot.resolve(SOURCE_FILE), SOURCE)
            val generatedRoot = Files.createDirectories(contentRoot.resolve(GENERATED_DIRECTORY))
            val generated = Files.writeString(generatedRoot.resolve(SOURCE_FILE), SOURCE)
            val virtualRoot = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(contentRoot)!!
            PsiTestUtil.addContentRoot(module, virtualRoot)
            val virtualSource = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(source)!!
            val virtualGenerated = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(generated)!!
            val content = FileContentImpl.createByText(virtualSource, SOURCE, project)
            assertTrue(DamlUsageScope.accepts(project, virtualSource))
            assertTrue(DamlSymbolIndex().getIndexer().map(content).containsKey(DamlSymbolIndex.MODULE_PREFIX + MODULE_NAME))
            assertFalse(DamlUsageScope.accepts(project, virtualGenerated))
        }
    }

    fun testQueryScopesExcludeGeneratedModuleAndSymbolCandidates() {
        myFixture.addFileToProject("daml.yaml", "name: index-scope-test\nsource: .\n")
        val source = myFixture.addFileToProject(SOURCE_FILE, SOURCE)
        myFixture.addFileToProject("$GENERATED_DIRECTORY/Generated.daml", "module Generated where\n$HIDDEN_SYMBOL = 1\n")
        myFixture.addFileToProject("out/$SOURCE_FILE", SOURCE)
        val resolver = DamlModuleResolver.getInstance(project)
        assertEquals(listOf(MODULE_NAME), resolver.moduleNames())
        assertEquals(source.virtualFile, resolver.resolveModule(MODULE_NAME, source.virtualFile)?.file)
        val contributor = DamlSymbolContributor()
        val scope = GlobalSearchScope.allScope(project)
        val names = mutableListOf<String>()
        contributor.processNames(Processor { names += it; true }, scope, null)
        assertContainsElements(names, VISIBLE_SYMBOL)
        assertFalse(names.contains(HIDDEN_SYMBOL))
        val targets = mutableListOf<NavigationItem>()
        contributor.processElementsWithName(HIDDEN_SYMBOL, Processor { targets += it; true }, FindSymbolParameters.wrap(HIDDEN_SYMBOL, scope))
        assertEmpty(targets)
        contributor.processElementsWithName(VISIBLE_SYMBOL, Processor { targets += it; true }, FindSymbolParameters.wrap(VISIBLE_SYMBOL, scope))
        assertEquals(listOf(source.virtualFile), targets.map { (it as DamlNamedElement).containingFile.virtualFile })
    }

    companion object {
        private const val GENERATED_DIRECTORY = "build"
        private const val HIDDEN_SYMBOL = "generatedAnswer"
        private const val VISIBLE_SYMBOL = "answer"
        private const val MODULE_NAME = "IndexedSource"
        private const val SOURCE_FILE = "$MODULE_NAME.daml"
        private const val SOURCE = "module $MODULE_NAME where\n$VISIBLE_SYMBOL = 42\n"
    }
}
