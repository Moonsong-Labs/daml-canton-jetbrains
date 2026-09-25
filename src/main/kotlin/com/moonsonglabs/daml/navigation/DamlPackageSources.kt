package com.moonsonglabs.daml.navigation

import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.JarFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiManager
import com.intellij.openapi.progress.ProgressManager
import com.moonsonglabs.daml.lang.DamlSourceModel
import java.util.concurrent.ConcurrentHashMap
import org.jetbrains.yaml.psi.YAMLFile
import org.jetbrains.yaml.psi.YAMLMapping
import org.jetbrains.yaml.psi.YAMLSequence
import org.jetbrains.yaml.psi.YAMLScalar

/** Resolve only the current package and the DARs explicitly selected by its configuration. */
@Service(Service.Level.PROJECT)
class DamlPackageSources(private val project: Project) {
    fun root(file: VirtualFile?): VirtualFile? = generateSequence(file?.parent) { it.parent }
        .firstOrNull { it.findChild(CONFIG) != null }

    fun sourceRoot(root: VirtualFile): VirtualFile? {
        val path = scalar(root.findChild(CONFIG), "source") ?: "daml"
        return root.findFileByRelativePath(path)
    }

    fun dependencies(context: VirtualFile?): List<VirtualFile> {
        val root = root(context) ?: return emptyList()
        val mapping = mapping(root.findChild(CONFIG)) ?: return emptyList()
        return listOf("dependencies", "data-dependencies").flatMap { key ->
            (mapping.getKeyValueByKey(key)?.value as? YAMLSequence)?.items.orEmpty().mapNotNull {
                val path = (it.value as? YAMLScalar)?.textValue ?: return@mapNotNull null
                if (!path.endsWith(".dar")) return@mapNotNull null
                (if (java.nio.file.Path.of(path).isAbsolute) com.intellij.openapi.vfs.LocalFileSystem.getInstance().findFileByPath(path) else root.findFileByRelativePath(path))?.let { dar -> JarFileSystem.getInstance().findFileByPath(dar.path + "!/") }
            }
        }.distinct()
    }

    private data class ArchiveModules(val stamp: Long, val modules: Map<String, List<VirtualFile>>)
    private val archives = ConcurrentHashMap<String, ArchiveModules>()
    fun modulesInArchive(root: VirtualFile, module: String): List<VirtualFile> {
        val archive = JarFileSystem.getInstance().getVirtualFileForJar(root) ?: return emptyList()
        val stamp = archive.modificationStamp
        val cached = archives[root.url]?.takeIf { it.stamp == stamp }
        val entry = cached ?: run {
            val modules = mutableMapOf<String, MutableList<VirtualFile>>()
            fun visit(file: VirtualFile) {
                ProgressManager.checkCanceled()
                if (file.isDirectory) file.children.forEach(::visit)
                else if (file.extension == "daml") {
                    val psi = PsiManager.getInstance(project).findFile(file) ?: return
                    val name = DamlSourceModel.get(psi).module ?: return
                    modules.getOrPut(name) { mutableListOf() }.add(file)
                }
            }
            visit(root)
            ArchiveModules(stamp, modules).also { archives[root.url] = it }
        }
        return entry.modules[module].orEmpty().filter { it.isValid }
    }

    fun samePackage(candidate: VirtualFile, context: VirtualFile?): Boolean {
        val owner = root(context)
        val candidateOwner = root(candidate)
        if (owner == null) return candidateOwner == null
        if (candidateOwner != owner) return false
        val source = sourceRoot(owner) ?: return false
        return candidate == source || candidate.path.startsWith(source.path + "/")
    }

    fun provenance(file: VirtualFile): String {
        if (file.fileSystem.protocol == "jar") return "Dependency source: ${file.path.substringBefore("!/").substringAfterLast('/')} (read-only)"
        val root = root(file) ?: return "Project source"
        val config = root.findChild(CONFIG)
        return listOfNotNull(scalar(config, "name"), scalar(config, "version"), scalar(config, "sdk-version")?.let { "SDK $it" }).joinToString(" · ")
    }
    private fun scalar(file: VirtualFile?, key: String) = (mapping(file)?.getKeyValueByKey(key)?.value as? YAMLScalar)?.textValue
    private fun mapping(file: VirtualFile?): YAMLMapping? = file?.let { PsiManager.getInstance(project).findFile(it) as? YAMLFile }
        ?.documents?.firstOrNull()?.topLevelValue as? YAMLMapping
    companion object { const val CONFIG = "daml.yaml" }
}
