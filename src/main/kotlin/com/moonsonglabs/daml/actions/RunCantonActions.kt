package com.moonsonglabs.daml.actions

import com.intellij.execution.RunManager
import com.intellij.execution.ProgramRunnerUtil
import com.intellij.execution.executors.DefaultRunExecutor
import com.intellij.execution.impl.RunManagerImpl
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.project.Project
import com.moonsonglabs.daml.run.CantonMode
import com.moonsonglabs.daml.run.CantonRunConfiguration
import com.moonsonglabs.daml.run.CantonRunConfigurationType
import com.moonsonglabs.daml.workspace.DamlWorkspaceService

abstract class RunCantonAction(private val mode: CantonMode) : AnAction() {
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val file = e.getData(CommonDataKeys.VIRTUAL_FILE)
        val type = com.intellij.execution.configurations.ConfigurationTypeUtil.findConfigurationType(CantonRunConfigurationType::class.java)
        val settings = RunManagerImpl.getInstanceImpl(project).createConfiguration(
            "Canton ${mode.presentableName}${file?.name?.let { " $it" } ?: ""}",
            type.configurationFactories.first()
        )
        val configuration = settings.configuration as CantonRunConfiguration
        configuration.mode = mode
        configuration.targetPath = file?.path.orEmpty()
        configuration.workspacePath = DamlWorkspaceService.getInstance(project).projectRoot()?.toString().orEmpty()
        RunManager.getInstance(project).selectedConfiguration = settings
        ProgramRunnerUtil.executeConfiguration(settings, DefaultRunExecutor.getRunExecutorInstance())
    }

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabled = e.project != null
    }
}

class RunCantonConfigAction : RunCantonAction(CantonMode.CONFIG)
class RunCantonScriptAction : RunCantonAction(CantonMode.SCRIPT)
