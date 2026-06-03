package com.moonsonglabs.daml.workspace

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.RootsChangeRescanningInfo
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.openapi.roots.ex.ProjectRootManagerEx
import com.intellij.testFramework.fixtures.BasePlatformTestCase

class DamlDirectoryExcludePolicyTest : BasePlatformTestCase() {
    fun testDamlPackageCacheDirectoryIsExcludedFromProjectSearch() {
        val cached = myFixture.addFileToProject(
            "vault-impl/.daml/package-database/2.2/vault-interface/Vault/Component/KYCPolicy.daml",
            "module Vault.Component.KYCPolicy where"
        )
        val source = myFixture.addFileToProject(
            "vault-impl/daml/Vault/Component/KYCPolicy.daml",
            "module Vault.Component.KYCPolicy where"
        )
        val index = ProjectFileIndex.getInstance(project)
        val excludedUrls = DamlDirectoryExcludePolicy(project).excludeUrlsForProject.toList()

        assertTrue(excludedUrls.any { it.endsWith("/vault-impl/.daml") })
        ApplicationManager.getApplication().runWriteAction(object : Runnable {
            override fun run() {
                ProjectRootManagerEx.getInstanceEx(project)
                    .makeRootsChange(Runnable {}, RootsChangeRescanningInfo.TOTAL_RESCAN)
            }
        })
        assertTrue(index.isExcluded(cached.virtualFile))
        assertFalse(index.isExcluded(source.virtualFile))
    }
}
