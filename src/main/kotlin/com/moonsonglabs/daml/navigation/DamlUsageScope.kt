package com.moonsonglabs.daml.navigation

import com.intellij.openapi.module.Module
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.SearchScope
import com.moonsonglabs.daml.DamlFileType
import com.moonsonglabs.daml.lang.DamlSymbolIndex

/** Search user sources only, even when the caller selects All Places or a local scope. */
class DamlUsageScope private constructor(project: Project) : GlobalSearchScope(project) {
    override fun contains(file: VirtualFile): Boolean = accepts(project!!, file)
    override fun isSearchInModuleContent(aModule: Module) = true
    override fun isSearchInLibraries() = false

    companion object {
        fun restrict(project: Project, scope: GlobalSearchScope): GlobalSearchScope = scope.intersectWith(DamlUsageScope(project))
        fun restrict(project: Project, scope: SearchScope): SearchScope = scope.intersectWith(DamlUsageScope(project))

        fun accepts(project: Project, file: VirtualFile?): Boolean {
            if (file == null || file.fileType != DamlFileType || file.fileSystem.protocol == "jar") return false
            // A checkout may itself live under a hidden parent (e.g. .worktrees). Only
            // entries inside the project/content root are hidden/generated search candidates.
            val contentRoot = ProjectFileIndex.getInstance(project).getContentRootForFile(file)
            val basePath = project.basePath?.trimEnd('/')
            val isUnderProject = basePath != null && file.path.startsWith("$basePath/")
            var current: VirtualFile? = file
            while (current != null && current.path != basePath) {
                if (!isUnderProject && current == contentRoot) break
                if (current.name.startsWith('.') || current.name in DamlSymbolIndex.IGNORED_DIRECTORIES) return false
                current = current.parent
            }
            return true
        }
    }
}
