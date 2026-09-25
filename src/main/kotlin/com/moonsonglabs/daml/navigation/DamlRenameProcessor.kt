package com.moonsonglabs.daml.navigation

import com.intellij.psi.PsiElement
import com.intellij.refactoring.rename.RenamePsiElementProcessor
import com.intellij.util.containers.MultiMap
import com.moonsonglabs.daml.DamlKeywords
import com.moonsonglabs.daml.lang.DamlNamedElement
import com.moonsonglabs.daml.lang.DamlSourceModel

class DamlRenameProcessor : RenamePsiElementProcessor() {
    override fun canProcessElement(element: PsiElement) = element is DamlNamedElement
    override fun prepareRenaming(element: PsiElement, newName: String, allRenames: MutableMap<PsiElement, String>) {
        val target = element as? DamlNamedElement ?: return
        val kind = target.symbol?.kind
        if (kind !in setOf(DamlSourceModel.Kind.FIELD, DamlSourceModel.Kind.METHOD)) return
        // Preflight every code occurrence before any write. Unknown compiler results abort the operation.
        val helper = com.intellij.psi.search.PsiSearchHelper.getInstance(element.project)
        val compiler = element.project.getService(DamlCompilerDefinitions::class.java)
        var failure: String? = null
        helper.processElementsWithWord({ candidate, offset ->
            if (!DamlUsageScope.accepts(element.project, candidate.containingFile.virtualFile)) return@processElementsWithWord true
            val leaf = candidate.containingFile.findElementAt(candidate.textOffset + offset) ?: return@processElementsWithWord true
            if (leaf.parent is DamlNamedElement) return@processElementsWithWord true
            val ref = DamlModuleResolver.referenceAt(leaf.containingFile, leaf.textOffset) ?: return@processElementsWithWord true
            val source = DamlSourceModel.get(leaf.containingFile)
            val line = source.lineAt(leaf.textOffset).tokens
            if (kind == DamlSourceModel.Kind.FIELD && (line.size == 1 || line.getOrNull(line.indexOfFirst { it.start == leaf.textOffset } - 1)?.text == "with")) {
                failure = "Expand record puns to explicit field assignments before renaming this field. No files were changed."
                return@processElementsWithWord false
            }
            val native = DamlModuleResolver.getInstance(element.project).resolveAll(ref, leaf.containingFile)
            if (native.size != 1 && compiler.resolve(leaf).isNullOrEmpty()) {
                failure = "Rename was stopped before editing: the compiler could not resolve every '${target.name}' occurrence. Start the DAML server and fix compilation errors, then retry."
                return@processElementsWithWord false
            }
            true
        }, DamlUsageScope.restrict(element.project, com.intellij.psi.search.GlobalSearchScope.projectScope(element.project)), target.name, com.intellij.psi.search.UsageSearchContext.IN_CODE, true)
        failure?.let { throw com.intellij.util.IncorrectOperationException(it) }
        if (kind == DamlSourceModel.Kind.METHOD) {
            com.intellij.psi.search.searches.DefinitionsScopedSearch.search(target).forEach { implementation -> allRenames[implementation] = newName }
        }
    }
    override fun forcesShowPreview() = true
    override fun isInplaceRenameSupported() = false
    override fun findExistingNameConflicts(element: PsiElement, newName: String, conflicts: MultiMap<PsiElement, String>) {
        val named = element as? DamlNamedElement ?: return
        val symbol = named.symbol ?: return
        if (!DamlSourceModel.isName(newName) || newName in DamlKeywords.haskellKeywords || newName.first().isUpperCase() != named.name.first().isUpperCase()) {
            conflicts.putValue(element, "The new name must be a DAML identifier with the same capitalization category.")
            return
        }
        val model = DamlSourceModel.get(element.containingFile)
        model.byName[newName].orEmpty().filter { other -> other.start != symbol.start && other.scopeStart < symbol.scopeEnd && symbol.scopeStart < other.scopeEnd }
            .forEach { conflicts.putValue(element, "Renaming to '$newName' would collide with or capture an existing binding.") }
        com.intellij.psi.search.searches.ReferencesSearch.search(named).forEach { usage ->
            val file = usage.element.containingFile
            val reference = DamlModuleResolver.referenceAt(file, usage.element.textOffset)
            if (reference != null && reference.qualifier == null && DamlSourceModel.get(file).declaration(reference.startOffset) == null) {
                val existing = DamlModuleResolver.getInstance(element.project).resolveAll(reference.copy(name = newName), file)
                if (existing.any { it != element }) conflicts.putValue(usage.element, "The new name '$newName' is already bound at this usage.")
            }
        }

    }
}
