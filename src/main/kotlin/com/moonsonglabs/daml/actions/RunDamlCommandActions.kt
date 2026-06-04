package com.moonsonglabs.daml.actions

import com.intellij.execution.RunManager
import com.intellij.execution.RunnerAndConfigurationSettings
import com.intellij.execution.ProgramRunnerUtil
import com.intellij.execution.executors.DefaultRunExecutor
import com.intellij.execution.impl.RunManagerImpl
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiManager
import com.moonsonglabs.daml.DamlFileType
import com.moonsonglabs.daml.run.DamlCommand
import com.moonsonglabs.daml.run.DamlRunConfiguration
import com.moonsonglabs.daml.run.DamlRunConfigurationType
import com.moonsonglabs.daml.workspace.DamlWorkspaceService

abstract class RunDamlCommandAction(private val command: DamlCommand) : AnAction() {
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val file = e.getData(CommonDataKeys.VIRTUAL_FILE)
        val configuration = createConfiguration(project, file?.path)
        val daml = configuration.configuration as DamlRunConfiguration
        daml.command = command
        file?.let {
            daml.filePath = it.path
            daml.workspacePath = DamlWorkspaceService.getInstance(project).workspaceFor(it)?.toString() ?: ""
            if (command == DamlCommand.SCRIPT) {
                daml.scriptName = PsiManager.getInstance(project).findFile(it)?.takeIf { psi -> psi.fileType === DamlFileType }
                    ?.let(::findScriptName)
                    .orEmpty()
            }
        }
        RunManager.getInstance(project).selectedConfiguration = configuration
        ProgramRunnerUtil.executeConfiguration(configuration, DefaultRunExecutor.getRunExecutorInstance())
    }

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabled = e.project != null
    }

    private fun createConfiguration(project: Project, path: String?): RunnerAndConfigurationSettings {
        val type = com.intellij.execution.configurations.ConfigurationTypeUtil.findConfigurationType(DamlRunConfigurationType::class.java)
        val factory = type.configurationFactories.first()
        return RunManagerImpl.getInstanceImpl(project).createConfiguration(
            "DAML ${command.presentableName}${path?.substringAfterLast('/')?.let { " $it" } ?: ""}",
            factory
        )
    }

    private fun findScriptName(file: com.intellij.psi.PsiFile): String {
        val module = Regex("""(?m)^\s*module\s+([A-Za-z0-9_.']+)\s+where\b""")
            .find(file.text)?.groupValues?.getOrNull(1)
        val script = Regex("""(?m)^\s*([a-zA-Z_][\w']*)\s*(?::[^\n]+)?=\s*script\b""")
            .find(file.text)?.groupValues?.getOrNull(1) ?: return ""
        return if (module.isNullOrBlank()) script else "$module:$script"
    }
}

class RunDamlBuildAction : RunDamlCommandAction(DamlCommand.BUILD)
class RunDamlTestAction : RunDamlCommandAction(DamlCommand.TEST)
class RunDamlScriptAction : RunDamlCommandAction(DamlCommand.SCRIPT)
class RunDamlStartAction : RunDamlCommandAction(DamlCommand.START)
