package com.moonsonglabs.daml.navigation

import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.TextRange
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.patterns.PlatformPatterns
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiManager
import com.intellij.psi.PsiReference
import com.intellij.psi.PsiReferenceBase
import com.intellij.psi.PsiReferenceContributor
import com.intellij.psi.PsiReferenceProvider
import com.intellij.psi.PsiReferenceRegistrar
import com.intellij.psi.search.FileTypeIndex
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.util.ProcessingContext
import com.moonsonglabs.daml.DamlFileType
import com.moonsonglabs.daml.DamlLanguage
import com.moonsonglabs.daml.DamlTokenTypes
import com.moonsonglabs.daml.workspace.DamlWorkspaceService

class DamlChoiceReferenceContributor : PsiReferenceContributor() {
    override fun registerReferenceProviders(registrar: PsiReferenceRegistrar) {
        registrar.registerReferenceProvider(
            PlatformPatterns.psiElement(DamlTokenTypes.TYPE_NAME).withLanguage(DamlLanguage),
            DamlChoiceReferenceProvider()
        )
        registrar.registerReferenceProvider(
            PlatformPatterns.psiElement(DamlTokenTypes.PRELUDE_TYPE).withLanguage(DamlLanguage),
            DamlChoiceReferenceProvider()
        )
    }
}

private class DamlChoiceReferenceProvider : PsiReferenceProvider() {
    override fun getReferencesByElement(element: PsiElement, context: ProcessingContext): Array<PsiReference> {
        val type = element.node?.elementType
        if (type != DamlTokenTypes.TYPE_NAME && type != DamlTokenTypes.PRELUDE_TYPE) return PsiReference.EMPTY_ARRAY

        val file = element.containingFile ?: return PsiReference.EMPTY_ARRAY
        val use = DamlChoiceNames.useAt(file.text, element.textRange.startOffset) ?: return PsiReference.EMPTY_ARRAY
        if (use.startOffset != element.textRange.startOffset || use.endOffset != element.textRange.endOffset) {
            return PsiReference.EMPTY_ARRAY
        }
        if (DamlChoiceResolver.getInstance(element.project).resolveChoiceUse(use, file.virtualFile) == null) {
            return PsiReference.EMPTY_ARRAY
        }

        return arrayOf(DamlChoiceReference(element, use))
    }
}

internal class DamlChoiceReference(
    element: PsiElement,
    private val use: DamlChoiceNames.ChoiceUse
) : PsiReferenceBase<PsiElement>(element, TextRange(0, element.textLength), true) {
    override fun resolve(): PsiElement? =
        DamlChoiceResolver.getInstance(element.project)
            .resolveChoiceUse(use, element.containingFile?.virtualFile)

    override fun getVariants(): Array<Any> =
        DamlChoiceResolver.getInstance(element.project).choiceNames().toTypedArray()
}

@Service(Service.Level.PROJECT)
class DamlChoiceResolver(private val project: Project) {
    fun resolveChoiceUse(use: DamlChoiceNames.ChoiceUse, contextFile: VirtualFile?): PsiElement? {
        val psiFile = contextFile?.let { PsiManager.getInstance(project).findFile(it) }
        val symbol = psiFile?.text?.let { DamlModuleNames.symbolAt(it, use.startOffset) }
        val imports = psiFile?.text?.let(DamlModuleNames::imports).orEmpty()

        symbol?.qualifier?.let { qualifier ->
            imports.firstOrNull { it.qualifierMatches(qualifier) }?.let { import ->
                return DamlModuleResolver.getInstance(project).resolveSymbol(import.moduleName, use.name, contextFile)
            }
            return DamlModuleResolver.getInstance(project).resolveSymbol(qualifier, use.name, contextFile)
        }

        resolveChoiceInFile(use.name, contextFile)?.let { return it }
        imports
            .asSequence()
            .filter { !it.qualified && it.exposes(use.name) }
            .firstNotNullOfOrNull { import ->
                DamlModuleResolver.getInstance(project).resolveSymbol(import.moduleName, use.name, contextFile)
            }
            ?.let { return it }

        return resolveChoice(use.name, contextFile)
    }

    fun resolveChoice(choiceName: String, contextFile: VirtualFile?): PsiElement? {
        resolveChoiceInFile(choiceName, contextFile)?.let { return it }

        val workspace = DamlWorkspaceService.getInstance(project).workspaceFor(contextFile)
        return choiceDeclarations()
            .filter { it.name == choiceName }
            .sortedWith(compareBy<ChoiceTarget> {
                val fileWorkspace = DamlWorkspaceService.getInstance(project).workspaceFor(it.file)
                if (workspace != null && fileWorkspace == workspace) 0 else 1
            }.thenBy { if (it.file == contextFile) 0 else 1 }.thenBy { it.file.path.length }.thenBy { it.file.path })
            .firstNotNullOfOrNull { target ->
                resolveChoiceInFile(choiceName, target.file)
            }
    }

    fun choiceNames(): List<String> =
        choiceDeclarations().map { it.name }.distinct().sorted()

    private fun choiceDeclarations(): List<ChoiceTarget> {
        val psiManager = PsiManager.getInstance(project)
        return FileTypeIndex.getFiles(DamlFileType, GlobalSearchScope.projectScope(project))
            .asSequence()
            .filter(::shouldIndex)
            .flatMap { file ->
                val psiFile = psiManager.findFile(file) ?: return@flatMap emptySequence()
                DamlChoiceNames.declarations(psiFile.text)
                    .asSequence()
                    .map { ChoiceTarget(it.name, file, it.startOffset) }
            }
            .toList()
    }

    private fun resolveChoiceInFile(choiceName: String, file: VirtualFile?): PsiElement? {
        val psiFile = file?.let { PsiManager.getInstance(project).findFile(it) } ?: return null
        val declaration = DamlChoiceNames.declarationNamed(psiFile.text, choiceName) ?: return null
        return psiFile.findElementAt(declaration.startOffset)
    }

    private fun shouldIndex(file: VirtualFile): Boolean {
        val parts = file.path.split('/')
        return parts.none {
            it == ".daml" || it == "build" || it == "out" || it == "node_modules" || it == ".gradle"
        }
    }

    private data class ChoiceTarget(
        val name: String,
        val file: VirtualFile,
        val offset: Int
    )

    companion object {
        fun getInstance(project: Project): DamlChoiceResolver = project.service()
    }
}
