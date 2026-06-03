package com.moonsonglabs.daml.workspace

import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectRootManager
import com.intellij.openapi.roots.impl.DirectoryIndexExcludePolicy
import com.intellij.openapi.vfs.VirtualFile

class DamlDirectoryExcludePolicy(private val project: Project) : DirectoryIndexExcludePolicy {
    override fun getExcludeUrlsForProject(): Array<String> =
        ProjectRootManager.getInstance(project).contentRoots
            .flatMap { root -> damlCacheDirs(root) }
            .distinctBy { it.url }
            .map { it.url }
            .toTypedArray()

    private fun damlCacheDirs(root: VirtualFile): List<VirtualFile> {
        if (!root.isDirectory) return emptyList()
        if (root.name == ".daml") return listOf(root)
        if (root.name in ignoredTraversalDirs) return emptyList()

        val result = mutableListOf<VirtualFile>()
        for (child in root.children) {
            if (!child.isDirectory) continue
            if (child.name == ".daml") {
                result.add(child)
            } else if (child.name !in ignoredTraversalDirs) {
                result.addAll(damlCacheDirs(child))
            }
        }
        return result
    }

    private val ignoredTraversalDirs = setOf(".git", ".idea", ".gradle", "build", "out", "node_modules")
}
