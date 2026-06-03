package com.moonsonglabs.daml.workspace

import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.io.path.name

@Service(Service.Level.PROJECT)
class DamlWorkspaceService(private val project: Project) {

    fun projectRoot(): Path? =
        project.basePath
            ?.let(Paths::get)
            ?.takeIf(Files::isDirectory)

    fun discoverWorkspaces(): List<Path> {
        val root = projectRoot() ?: return emptyList()
        if (!Files.isDirectory(root)) return emptyList()
        val found = linkedSetOf<Path>()

        if (isDamlWorkspace(root)) found.add(root)
        Files.walk(root, 8).use { paths ->
            paths
                .filter { Files.isRegularFile(it) }
                .filter { it.name == "daml.yaml" || it.name == "multi-package.yaml" }
                .forEach { found.add(it.parent) }
        }
        return found
            .filter { shouldKeepWorkspace(root, it) }
            .sortedWith(compareBy<Path> { root.relativize(it).nameCount }.thenBy { it.toString() })
    }

    fun defaultWorkspace(): Path? =
        discoverWorkspaces().firstOrNull() ?: projectRoot()?.takeIf(::isDamlWorkspace)

    fun defaultPackageWorkspace(): Path? =
        discoverWorkspaces().firstOrNull { Files.exists(it.resolve("daml.yaml")) }

    fun workspaceFor(file: VirtualFile?): Path? {
        val root = projectRoot() ?: return defaultWorkspace()
        val start = file?.let(::toPathOrNull)?.let { if (Files.isDirectory(it)) it else it.parent } ?: root
        if (start.any { it.name in ignoredPathNames }) return defaultWorkspace()
        var cursor: Path? = start
        while (cursor != null && cursor.normalize().startsWith(root.normalize())) {
            if (isDamlWorkspace(cursor)) return cursor
            cursor = cursor.parent
        }
        return defaultWorkspace()
    }

    fun isDamlWorkspace(path: Path): Boolean =
        Files.exists(path.resolve("daml.yaml")) || Files.exists(path.resolve("multi-package.yaml"))

    private fun shouldKeepWorkspace(root: Path, workspace: Path): Boolean {
        val rel = runCatching { root.relativize(workspace).toString() }.getOrDefault("")
        return rel.split(java.io.File.separatorChar).none {
            it in ignoredPathNames
        }
    }

    private fun toPathOrNull(file: VirtualFile): Path? =
        runCatching { file.toNioPath() }.getOrNull()

    private val ignoredPathNames = setOf(".daml", "build", "out", "node_modules", ".gradle")

    companion object {
        fun getInstance(project: Project): DamlWorkspaceService = project.service()
    }
}
