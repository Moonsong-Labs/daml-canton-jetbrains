package com.moonsonglabs.daml.navigation

import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.util.indexing.FileBasedIndex
import com.moonsonglabs.daml.lang.DamlNamedElement
import com.moonsonglabs.daml.lang.DamlSourceModel
import com.moonsonglabs.daml.lang.DamlSymbolIndex

@Service(Service.Level.PROJECT)
class DamlModuleResolver(private val project: Project) {
    data class ResolvedModule(val moduleName: String, val file: VirtualFile, val target: PsiElement)
    private val packages get() = project.service<DamlPackageSources>()

    fun resolve(moduleName: String, contextFile: VirtualFile?): PsiElement? = resolveModule(moduleName, contextFile)?.target
    fun resolveImport(import: DamlModuleNames.ImportReference, contextFile: VirtualFile?): PsiElement? =
        if (import.isSymbolReference()) resolveSymbol(import.moduleName, import.symbolName.orEmpty(), contextFile) else resolve(import.moduleName, contextFile)

    fun resolveSymbolReference(reference: DamlModuleNames.SymbolReference, contextFile: VirtualFile?): PsiElement? =
        resolveAll(reference, contextFile).singleOrNull()

    fun resolveAll(reference: DamlModuleNames.SymbolReference, contextFile: VirtualFile?): List<PsiElement> {
        val file = contextFile?.let { PsiManager.getInstance(project).findFile(it) } ?: return emptyList()
        return resolveAll(reference, file)
    }
    fun resolveAll(reference: DamlModuleNames.SymbolReference, file: PsiFile): List<PsiElement> {
        val model = DamlSourceModel.get(file)
        model.declaration(reference.startOffset)?.let { declaration ->
            return if (declaration.start == reference.startOffset) emptyList() else listOfNotNull(DamlNamedElement.at(file, declaration.start))
        }
        model.imports.firstOrNull { reference.startOffset in it.moduleEnd until it.end && reference.name in it.names.orEmpty() }?.let {
            return preferNamespace(symbolTargets(it.module, reference.name, file.virtualFile), true)
        }
        if (reference.qualifier == null) {
            val localCandidates = model.byName[reference.name].orEmpty().filter { it.visibleAt(reference.startOffset) && it.kind != DamlSourceModel.Kind.INSTANCE }
            val local = if (isTypePosition(model, reference.startOffset)) {
                localCandidates.filter { it.kind in TYPE_KINDS }.minByOrNull { it.scopeEnd - it.scopeStart }
            } else model.local(reference.name, reference.startOffset)
            local?.let { return listOfNotNull(DamlNamedElement.at(file, it.start)) }
            // Choices and constructors are module-level values even when nested in a template.
            model.byName[reference.name].orEmpty().filter { it.kind in setOf(DamlSourceModel.Kind.CHOICE, DamlSourceModel.Kind.CONSTRUCTOR) }
                .mapNotNull { DamlNamedElement.at(file, it.start) }.takeIf { it.isNotEmpty() }?.let { return it }
        } else if (reference.qualifier.firstOrNull()?.isLowerCase() == true) {
            // A record receiver requires compiler type information. Never resolve it as a module.
            return emptyList()
        }
        val imported = model.imports.filter { if (reference.qualifier == null) !it.qualified else it.matches(reference.qualifier) }
            .flatMap { import -> symbolTargets(import.module, reference.name, file.virtualFile).filter { target ->
                val named = target as? DamlNamedElement ?: return@filter false
                val targetModel = DamlSourceModel.get(named.containingFile)
                import.exposes(reference.name, named.symbol?.let { targetModel.owner(it)?.name })
            } }.distinct()
        return preferNamespace(imported, isTypePosition(model, reference.startOffset))
    }
    private fun preferNamespace(targets: List<PsiElement>, typePosition: Boolean): List<PsiElement> {
        val preferred = targets.filter { ((it as? DamlNamedElement)?.symbol?.kind in TYPE_KINDS) == typePosition }
        return preferred.ifEmpty { targets }
    }
    private fun isTypePosition(model: DamlSourceModel, offset: Int): Boolean {
        val tokens = model.lineAt(offset).tokens
        val before = tokens.takeWhile { it.start < offset }.map { it.text }
        return before.lastOrNull() == "@" || before.firstOrNull() == "type" ||
            (":" in before && "=" !in before)
    }
    fun resolveSymbol(moduleName: String, symbolName: String, contextFile: VirtualFile?): PsiElement? =
        symbolTargets(moduleName, symbolName, contextFile).singleOrNull()

    private fun symbolTargets(module: String, name: String, context: VirtualFile?): List<PsiElement> = moduleFiles(module, context).flatMap { file ->
        val psi = PsiManager.getInstance(project).findFile(file) ?: return@flatMap emptyList()
        val model = DamlSourceModel.get(psi)
        model.byName[name].orEmpty().filter { model.exported(it) }.mapNotNull { DamlNamedElement.at(psi, it.start) }
    }
    fun resolveModule(moduleName: String, contextFile: VirtualFile?): ResolvedModule? = moduleFiles(moduleName, contextFile).singleOrNull()?.let { file ->
        val psi = PsiManager.getInstance(project).findFile(file) ?: return null
        ResolvedModule(moduleName, file, DamlSourceModel.get(psi).moduleStart?.let(psi::findElementAt) ?: psi)
    }
    fun moduleNames(): List<String> = if (DumbService.isDumb(project)) emptyList() else
        FileBasedIndex.getInstance().getAllKeys(DamlSymbolIndex.NAME, project).filter { it.startsWith(DamlSymbolIndex.MODULE_PREFIX) }.map { it.removePrefix(DamlSymbolIndex.MODULE_PREFIX) }.sorted()

    private fun moduleFiles(module: String, context: VirtualFile?): List<VirtualFile> {
        ProgressManager.checkCanceled()
        val own = if (DumbService.isDumb(project)) emptyList() else FileBasedIndex.getInstance()
            .getContainingFiles(DamlSymbolIndex.NAME, DamlSymbolIndex.MODULE_PREFIX + module, GlobalSearchScope.projectScope(project))
            .filter { packages.samePackage(it, context) }
        if (own.isNotEmpty()) return own
        // DAR sources keep their immutable archive identity, including the selected package version.
        return packages.dependencies(context).flatMap { packages.modulesInArchive(it, module) }
    }
    companion object {
        private val TYPE_KINDS = setOf(DamlSourceModel.Kind.DATA, DamlSourceModel.Kind.NEWTYPE, DamlSourceModel.Kind.TYPE,
            DamlSourceModel.Kind.TEMPLATE, DamlSourceModel.Kind.INTERFACE, DamlSourceModel.Kind.CLASS, DamlSourceModel.Kind.TYPE_PARAMETER)
        fun getInstance(project: Project): DamlModuleResolver = project.service()
        fun referenceAt(file: PsiFile, offset: Int): DamlModuleNames.SymbolReference? {
            val model = DamlSourceModel.get(file)
            val token = model.tokenAt(offset)?.takeIf { it.code && DamlSourceModel.isName(it.text) } ?: return null
            if (model.text.getOrNull(token.end) == '.') return null
            var start = token.start
            if (model.text.getOrNull(start - 1) == '.') {
                start -= 2
                while (start >= 0 && (model.text[start].isLetterOrDigit() || model.text[start] in "_'.")) start--
                start++
            }
            val qualifier = if (start < token.start) model.text.substring(start, token.start - 1) else null
            return DamlModuleNames.SymbolReference(token.text, token.start, token.end, qualifier, start, token.start - 1)
        }
    }
}
