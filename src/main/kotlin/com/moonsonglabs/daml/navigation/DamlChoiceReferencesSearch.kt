package com.moonsonglabs.daml.navigation

import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiManager
import com.intellij.psi.PsiReference
import com.intellij.psi.search.FileTypeIndex
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.searches.ReferencesSearch
import com.intellij.util.Processor
import com.intellij.util.QueryExecutor
import com.moonsonglabs.daml.DamlFileType

class DamlChoiceReferencesSearch : QueryExecutor<PsiReference, ReferencesSearch.SearchParameters> {
    override fun execute(
        queryParameters: ReferencesSearch.SearchParameters,
        consumer: Processor<in PsiReference>
    ): Boolean {
        val target = queryParameters.elementToSearch
        val declaration = choiceDeclarationFor(target) ?: return true
        val project = target.project
        val psiManager = PsiManager.getInstance(project)
        val scope = queryParameters.effectiveSearchScope as? GlobalSearchScope ?: GlobalSearchScope.projectScope(project)

        for (file in FileTypeIndex.getFiles(DamlFileType, scope)) {
            if (!shouldIndex(file)) continue
            val psiFile = psiManager.findFile(file) ?: continue
            for (use in DamlChoiceNames.uses(psiFile.text).filter { it.name == declaration.name }) {
                val element = psiFile.findElementAt(use.startOffset) ?: continue
                val reference = DamlChoiceReference(element, use)
                val resolved = reference.resolve()
                if (sameChoiceDeclaration(resolved, declaration) && !consumer.process(reference)) return false
            }
        }
        return true
    }

    private fun choiceDeclarationFor(element: PsiElement): ChoiceDeclarationTarget? {
        val target = DamlChoiceUsageTargets.fromElement(element) ?: return null
        return ChoiceDeclarationTarget(target.name, target.file, target.offset)
    }

    private fun sameChoiceDeclaration(element: PsiElement?, declaration: ChoiceDeclarationTarget): Boolean =
        element?.containingFile?.virtualFile == declaration.file &&
            element.textRange.startOffset == declaration.offset

    private fun shouldIndex(file: VirtualFile): Boolean {
        val parts = file.path.split('/')
        return parts.none {
            it == ".daml" || it == "build" || it == "out" || it == "node_modules" || it == ".gradle"
        }
    }

    private data class ChoiceDeclarationTarget(
        val name: String,
        val file: VirtualFile,
        val offset: Int
    )
}
