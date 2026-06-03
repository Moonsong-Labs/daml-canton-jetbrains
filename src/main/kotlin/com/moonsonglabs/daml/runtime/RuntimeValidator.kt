package com.moonsonglabs.daml.runtime

import com.intellij.execution.ExecutionException
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.moonsonglabs.daml.lsp.DamlBinaryLocator
import com.moonsonglabs.daml.settings.DamlProjectSettings
import com.moonsonglabs.daml.workspace.DamlWorkspaceService
import java.nio.file.Files
import java.nio.file.Path

@Service(Service.Level.PROJECT)
class RuntimeValidator(private val project: Project) {

    data class ToolCheck(val name: String, val ok: Boolean, val detail: String)
    data class Result(
        val checks: List<ToolCheck>,
        val workspace: Path?,
        val message: String
    ) {
        val ok: Boolean get() = checks.filter { it.name != "dpm" }.all { it.ok }
    }

    fun validate(): Result {
        val settings = DamlProjectSettings.getInstance(project)
        val workspace = DamlWorkspaceService.getInstance(project).defaultWorkspace()
            ?: DamlWorkspaceService.getInstance(project).projectRoot()
        val checks = listOf(
            checkDaml(settings, workspace),
            checkTool("dpm", settings, required = false),
            checkTool("canton", settings, override = settings.cantonBinaryPath.takeIf { it.isNotBlank() }),
            checkTool("java", settings),
            checkTool("node", settings)
        )
        val missing = checks.filterNot { it.ok }.filter { it.name != "dpm" }.joinToString(", ") { it.name }
        val message = if (missing.isNotBlank()) {
            "Missing runtime tools: $missing"
        } else {
            "Local Canton/DAML runtime looks ready."
        }
        settings.lastRuntimeValidation = message
        return Result(checks, workspace, message)
    }

    fun requireReadyForRun() {
        val result = validate()
        if (!result.ok) {
            throw ExecutionException(result.message)
        }
    }

    fun requireDamlReadyForRun() =
        requireTools(setOf("daml/dpm", "java"))

    fun requireCantonReadyForRun() =
        requireTools(setOf("canton", "java"))

    private fun requireTools(requiredNames: Set<String>) {
        val result = validate()
        val missing = result.checks
            .filter { it.name in requiredNames }
            .filterNot { it.ok }
        if (missing.isNotEmpty()) {
            throw ExecutionException("Missing runtime tools: ${missing.joinToString(", ") { it.name }}")
        }
    }

    private fun checkDaml(settings: DamlProjectSettings, workspace: Path?): ToolCheck {
        val override = settings.binaryPath.takeIf { it.isNotBlank() }?.let { Path.of(it) }
        if (override != null) {
            return if (Files.isExecutable(override)) {
                ToolCheck("daml/dpm", true, override.toString())
            } else {
                ToolCheck("daml/dpm", false, "override is not executable: $override")
            }
        }
        val located = DamlBinaryLocator.locate(project, workspace)?.binary
        return if (located != null) {
            ToolCheck("daml/dpm", true, located.toString())
        } else {
            ToolCheck("daml/dpm", false, "not found")
        }
    }

    private fun checkTool(
        name: String,
        settings: DamlProjectSettings,
        override: String? = null,
        required: Boolean = true
    ): ToolCheck {
        override?.let {
            val path = Path.of(it)
            if (name == "canton" && path.toString().endsWith(".jar") && Files.isRegularFile(path)) {
                return ToolCheck(name, true, path.toString())
            }
            return if (Files.isExecutable(path)) {
                ToolCheck(name, true, path.toString())
            } else {
                ToolCheck(name, false, "override is not executable: $path")
            }
        }
        val path = RuntimeEnvironment.findExecutable(name, settings)
        if (path != null) return ToolCheck(name, true, path.toString())
        return ToolCheck(name, !required, if (required) "not found" else "optional")
    }

    companion object {
        fun getInstance(project: Project): RuntimeValidator = project.service()
    }
}
