package com.moonsonglabs.daml.navigation

import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiManager
import com.intellij.psi.util.PsiModificationTracker
import com.intellij.psi.search.FileTypeIndex
import com.intellij.psi.search.GlobalSearchScope
import com.moonsonglabs.daml.DamlFileType
import com.moonsonglabs.daml.workspace.DamlWorkspaceService
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.name

@Service(Service.Level.PROJECT)
class DamlModuleResolver(private val project: Project) {
    data class ResolvedModule(val moduleName: String, val file: VirtualFile, val target: PsiElement)

    fun resolve(moduleName: String, contextFile: VirtualFile?): PsiElement? =
        resolveModule(moduleName, contextFile)?.target

    fun resolveImport(import: DamlModuleNames.ImportReference, contextFile: VirtualFile?): PsiElement? {
        if (!import.isSymbolReference()) return resolve(import.moduleName, contextFile)
        return resolveSymbol(import.moduleName, import.symbolName.orEmpty(), contextFile)
    }

    fun resolveSymbolReference(reference: DamlModuleNames.SymbolReference, contextFile: VirtualFile?): PsiElement? {
        val contextPsiFile = contextFile?.let { PsiManager.getInstance(project).findFile(it) }
        if (contextPsiFile != null &&
            DamlModuleNames.declarationAt(contextPsiFile.text, reference.startOffset)?.name == reference.name
        ) {
            return null
        }

        if (contextPsiFile != null && reference.qualifier == null) {
            DamlLocalBindings.resolve(contextPsiFile.text, reference)?.let { binding ->
                return contextPsiFile.findElementAt(binding.startOffset)
            }
            resolveSymbolInFile(contextPsiFile.virtualFile, reference.name)?.let { return it }
        }

        val imports = contextPsiFile?.text?.let(DamlModuleNames::imports).orEmpty()
        reference.qualifier?.let { qualifier ->
            imports.firstOrNull { it.qualifierMatches(qualifier) }?.let { import ->
                return resolveSymbol(import.moduleName, reference.name, contextFile)
            }
            return resolveSymbol(qualifier, reference.name, contextFile)
        }

        imports
            .asSequence()
            .filter { !it.qualified && it.symbols.isNotEmpty() && !it.hiding && reference.name in it.symbols }
            .firstNotNullOfOrNull { resolveSymbol(it.moduleName, reference.name, contextFile) }
            ?.let { return it }

        imports
            .asSequence()
            .filter { !it.qualified && it.exposes(reference.name) }
            .firstNotNullOfOrNull { resolveSymbol(it.moduleName, reference.name, contextFile) }
            ?.let { return it }

        return null
    }

    fun resolveSymbol(moduleName: String, symbolName: String, contextFile: VirtualFile?): PsiElement? =
        resolveSymbolDeclaration(moduleName, symbolName, contextFile)

    private fun resolveSymbolDeclaration(moduleName: String, symbolName: String, contextFile: VirtualFile?): PsiElement? {
        val module = resolveModule(moduleName, contextFile) ?: return null
        val psiFile = PsiManager.getInstance(project).findFile(module.file) ?: return null
        val declaration = DamlModuleNames.navigableDeclarationNamed(psiFile.text, symbolName) ?: return null
        return psiFile.findElementAt(declaration.startOffset)
    }

    fun resolveModule(moduleName: String, contextFile: VirtualFile?): ResolvedModule? {
        val workspace = DamlWorkspaceService.getInstance(project).workspaceFor(contextFile)
        return moduleFiles()
            .filter { it.moduleName == moduleName }
            .sortedWith(compareBy<ModuleFile> {
                val fileWorkspace = DamlWorkspaceService.getInstance(project).workspaceFor(it.file)
                if (workspace != null && fileWorkspace == workspace) 0 else 1
            }.thenBy { it.file.path.length }.thenBy { it.file.path })
            .firstOrNull()
            ?.toResolvedModule()
    }

    fun moduleNames(): List<String> =
        moduleFiles().map { it.moduleName }.distinct().sorted()

    @Synchronized
    private fun moduleFiles(): List<ModuleFile> {
        val psiStamp = PsiModificationTracker.getInstance(project).modificationCount
        val vfsStamp = VirtualFileManager.VFS_STRUCTURE_MODIFICATIONS.modificationCount
        moduleCache?.takeIf { it.psiStamp == psiStamp && it.vfsStamp == vfsStamp }?.let { return it.modules }

        val modules = loadModuleFiles()
        moduleCache = ModuleCache(psiStamp, vfsStamp, modules)
        return modules
    }

    private fun loadModuleFiles(): List<ModuleFile> {
        val psiManager = PsiManager.getInstance(project)
        val indexedModules = FileTypeIndex.getFiles(DamlFileType, GlobalSearchScope.projectScope(project))
            .asSequence()
            .filter(::shouldIndex)
            .mapNotNull { file -> moduleFile(psiManager, file) }
            .toList()
        return (indexedModules + relatedWorkspaceModuleFiles(psiManager))
            .distinctBy { it.file.path }
    }

    private fun resolveSymbolInFile(file: VirtualFile?, symbolName: String): PsiElement? {
        val psiFile = file?.let { PsiManager.getInstance(project).findFile(it) } ?: return null
        val declaration = DamlModuleNames.navigableDeclarationNamed(psiFile.text, symbolName) ?: return null
        return psiFile.findElementAt(declaration.startOffset)
    }

    private fun ModuleFile.toResolvedModule(): ResolvedModule? {
        val psiFile = PsiManager.getInstance(project).findFile(file) ?: return null
        val declaration = DamlModuleNames.declaredModule(psiFile.text) ?: return null
        val target = psiFile.findElementAt(declaration.startOffset) ?: psiFile
        return ResolvedModule(declaration.name, file, target)
    }

    private fun moduleFile(psiManager: PsiManager, file: VirtualFile): ModuleFile? {
        val psiFile = psiManager.findFile(file) ?: return null
        val declaration = DamlModuleNames.declaredModule(psiFile.text) ?: return null
        return ModuleFile(declaration.name, file)
    }

    private fun relatedWorkspaceModuleFiles(psiManager: PsiManager): List<ModuleFile> {
        val localFileSystem = LocalFileSystem.getInstance()
        return relatedWorkspaces()
            .flatMap(::sourceDirs)
            .asSequence()
            .filter { Files.isDirectory(it) }
            .flatMap { dir -> damlSourcePaths(dir).asSequence() }
            .mapNotNull { path -> localFileSystem.refreshAndFindFileByNioFile(path) }
            .filter(::shouldIndex)
            .mapNotNull { file -> moduleFile(psiManager, file) }
            .toList()
    }

    private fun relatedWorkspaces(): List<Path> {
        val workspaceService = DamlWorkspaceService.getInstance(project)
        val root = workspaceService.projectRoot() ?: return emptyList()
        val workspaces = linkedSetOf<Path>()

        workspaces.addAll(workspaceService.discoverWorkspaces())
        if (workspaceService.isDamlWorkspace(root)) workspaces.add(root)

        root.parent?.takeIf { Files.isDirectory(it) }?.let { parent ->
            runCatching {
                Files.list(parent).use { siblings ->
                    siblings
                        .filter { Files.isDirectory(it) }
                        .filter { workspaceService.isDamlWorkspace(it) }
                        .forEach { workspaces.add(it) }
                }
            }
        }

        nearbyMultiPackageRoots(root).forEach { multiPackageRoot ->
            workspaces.addAll(multiPackageWorkspaces(multiPackageRoot))
        }

        return workspaces
            .filter { it.isNavigableWorkspacePath() }
            .toList()
    }

    private fun damlSourcePaths(dir: Path): List<Path> =
        runCatching {
            Files.walk(dir).use { paths ->
                paths.iterator().asSequence()
                    .filter { it.isDamlSourcePath() }
                    .toList()
            }
        }.getOrDefault(emptyList())

    private fun nearbyMultiPackageRoots(root: Path): List<Path> =
        generateSequence(root) { it.parent }
            .take(4)
            .filter { Files.isRegularFile(it.resolve("multi-package.yaml")) }
            .toList()

    private fun multiPackageWorkspaces(root: Path): List<Path> {
        val config = root.resolve("multi-package.yaml")
        if (!Files.isRegularFile(config)) return emptyList()
        return runCatching { Files.readAllLines(config) }.getOrDefault(emptyList())
            .asSequence()
            .mapNotNull { line -> Regex("""^\s*-\s+(.+?)\s*$""").find(line)?.groups?.get(1)?.value }
            .map { it.trim().trim('"', '\'') }
            .map { root.resolve(it).normalize() }
            .filter { DamlWorkspaceService.getInstance(project).isDamlWorkspace(it) }
            .toList()
    }

    private fun sourceDirs(workspace: Path): List<Path> {
        val damlYaml = workspace.resolve("daml.yaml")
        val source = if (Files.isRegularFile(damlYaml)) {
            runCatching { Files.readAllLines(damlYaml) }.getOrDefault(emptyList())
                .firstNotNullOfOrNull { line -> Regex("""^\s*source\s*:\s*(.+?)\s*$""").find(line)?.groups?.get(1)?.value }
                ?.trim()
                ?.trim('"', '\'')
                ?: "daml"
        } else {
            "daml"
        }
        return listOf(workspace.resolve(source).normalize())
    }

    private fun Path.isDamlSourcePath(): Boolean =
        Files.isRegularFile(this) && name.endsWith(".daml") && isNavigableWorkspacePath()

    private fun Path.isNavigableWorkspacePath(): Boolean =
        none { part -> part.name in ignoredPathNames }

    private fun shouldIndex(file: VirtualFile): Boolean {
        val path = file.path
        val parts = path.split('/')
        return parts.none { it in ignoredPathNames }
    }

    private val ignoredPathNames = setOf(".daml", "build", "out", "node_modules", ".gradle")

    private data class ModuleFile(
        val moduleName: String,
        val file: VirtualFile
    )

    private data class ModuleCache(
        val psiStamp: Long,
        val vfsStamp: Long,
        val modules: List<ModuleFile>
    )

    private var moduleCache: ModuleCache? = null

    companion object {
        fun getInstance(project: Project): DamlModuleResolver = project.service()
    }
}
