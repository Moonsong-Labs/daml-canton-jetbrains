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
        Files.walkFileTree(root, emptySet(), 8, object : java.nio.file.SimpleFileVisitor<Path>() {
            override fun preVisitDirectory(dir: Path, attrs: java.nio.file.attribute.BasicFileAttributes): java.nio.file.FileVisitResult {
                com.intellij.openapi.progress.ProgressManager.checkCanceled()
                return if (dir != root && dir.name in ignoredPathNames) java.nio.file.FileVisitResult.SKIP_SUBTREE else java.nio.file.FileVisitResult.CONTINUE
            }
            override fun visitFile(file: Path, attrs: java.nio.file.attribute.BasicFileAttributes): java.nio.file.FileVisitResult {
                if (file.name == "daml.yaml" || file.name == "multi-package.yaml") found.add(file.parent)
                return java.nio.file.FileVisitResult.CONTINUE
            }
        })
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
        if (isIgnoredPath(root, start)) return null
        var cursor: Path? = start
        while (cursor != null && cursor.normalize().startsWith(root.normalize())) {
            if (isDamlWorkspace(cursor)) return cursor
            cursor = cursor.parent
        }
        return defaultWorkspace()
    }

    fun isDamlWorkspace(path: Path): Boolean =
        Files.exists(path.resolve("daml.yaml")) || Files.exists(path.resolve("multi-package.yaml"))

    private fun shouldKeepWorkspace(root: Path, workspace: Path): Boolean = !isIgnoredPath(root, workspace)

    private fun toPathOrNull(file: VirtualFile): Path? =
        runCatching { file.toNioPath() }.getOrNull()

    companion object {
        private val ignoredPathNames = setOf(".daml", "build", "out", "node_modules", ".gradle")

        internal fun isIgnoredPath(root: Path, path: Path): Boolean {
            val normalizedRoot = root.toAbsolutePath().normalize()
            val normalizedPath = path.toAbsolutePath().normalize()
            return normalizedPath.startsWith(normalizedRoot) &&
                normalizedRoot.relativize(normalizedPath).any { it.name in ignoredPathNames }
        }

        fun getInstance(project: Project): DamlWorkspaceService = project.service()
    }
}
