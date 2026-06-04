package com.moonsonglabs.daml

import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import com.moonsonglabs.daml.lsp.DamlBinaryLocator
import com.moonsonglabs.daml.workspace.DamlWorkspaceService

/**
 * On project open: detect whether the workspace is a DAML project (has daml.yaml at any
 * directory we can find) and surface a friendly notification if the SDK is missing.
 *
 * Why: the LSP server itself produces empty diagnostics if cwd is not a daml project root,
 * which is confusing. Failing fast with a clear message is the kinder default.
 */
class DamlStartupActivity : ProjectActivity {
    override suspend fun execute(project: Project) {
        val hasDaml = DamlWorkspaceService.getInstance(project).discoverWorkspaces().isNotEmpty()
        if (!hasDaml) return

        val binary = DamlBinaryLocator.locate(project)
        if (binary == null) {
            DamlNotifier.warn(project, DamlBundle.message("daml.notification.sdk.notFound"))
        }
    }
}
