package com.moonsonglabs.daml.run

import com.intellij.execution.actions.ConfigurationContext
import com.intellij.execution.actions.LazyRunConfigurationProducer
import com.intellij.execution.configurations.ConfigurationTypeUtil
import com.intellij.openapi.util.Ref
import com.intellij.psi.PsiElement
import com.moonsonglabs.daml.canton.CantonFileType
import com.moonsonglabs.daml.workspace.DamlWorkspaceService

class CantonRunConfigurationProducer : LazyRunConfigurationProducer<CantonRunConfiguration>() {
    override fun getConfigurationFactory() =
        ConfigurationTypeUtil.findConfigurationType(CantonRunConfigurationType::class.java).configurationFactories.first()

    override fun setupConfigurationFromContext(
        configuration: CantonRunConfiguration,
        context: ConfigurationContext,
        sourceElement: Ref<PsiElement>
    ): Boolean {
        val psiFile = context.psiLocation?.containingFile ?: return false
        if (psiFile.fileType !== CantonFileType) return false
        val file = psiFile.virtualFile ?: return false
        configuration.workspacePath = DamlWorkspaceService.getInstance(context.project).projectRoot()?.toString() ?: ""
        configuration.targetPath = file.path
        configuration.mode = if (file.extension == "conf") CantonMode.CONFIG else CantonMode.SCRIPT
        configuration.name = "Canton ${configuration.mode.presentableName} ${file.name}"
        return true
    }

    override fun isConfigurationFromContext(configuration: CantonRunConfiguration, context: ConfigurationContext): Boolean {
        val file = context.psiLocation?.containingFile?.virtualFile ?: return false
        return configuration.targetPath == file.path
    }
}
