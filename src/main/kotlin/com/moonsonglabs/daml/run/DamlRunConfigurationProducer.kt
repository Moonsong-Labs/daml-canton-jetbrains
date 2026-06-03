package com.moonsonglabs.daml.run

import com.intellij.execution.actions.ConfigurationContext
import com.intellij.execution.actions.LazyRunConfigurationProducer
import com.intellij.execution.configurations.ConfigurationTypeUtil
import com.intellij.openapi.util.Ref
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.moonsonglabs.daml.DamlFileType
import com.moonsonglabs.daml.workspace.DamlWorkspaceService

class DamlRunConfigurationProducer : LazyRunConfigurationProducer<DamlRunConfiguration>() {
    override fun getConfigurationFactory() =
        ConfigurationTypeUtil.findConfigurationType(DamlRunConfigurationType::class.java).configurationFactories.first()

    override fun setupConfigurationFromContext(
        configuration: DamlRunConfiguration,
        context: ConfigurationContext,
        sourceElement: Ref<PsiElement>
    ): Boolean {
        val psiFile = context.psiLocation?.containingFile ?: return false
        val file = psiFile.virtualFile ?: return false
        val workspace = DamlWorkspaceService.getInstance(context.project).workspaceFor(file) ?: return false
        configuration.workspacePath = workspace.toString()
        configuration.filePath = file.path
        when {
            file.name == "daml.yaml" || file.name == "multi-package.yaml" -> {
                configuration.command = DamlCommand.BUILD
                configuration.name = "DAML Build"
            }
            psiFile.fileType === DamlFileType -> {
                val script = findScriptName(psiFile)
                configuration.command = if (script != null) DamlCommand.SCRIPT else DamlCommand.TEST
                configuration.scriptName = script ?: ""
                configuration.name = if (script != null) "DAML Script $script" else "DAML Test ${file.name}"
            }
            else -> return false
        }
        return true
    }

    override fun isConfigurationFromContext(configuration: DamlRunConfiguration, context: ConfigurationContext): Boolean {
        val file = context.psiLocation?.containingFile?.virtualFile ?: return false
        return configuration.filePath == file.path || configuration.workspacePath == DamlWorkspaceService.getInstance(context.project).workspaceFor(file)?.toString()
    }

    private fun findScriptName(file: PsiFile): String? {
        val module = Regex("""(?m)^\s*module\s+([A-Za-z0-9_.']+)\s+where\b""")
            .find(file.text)?.groupValues?.getOrNull(1)
        val script = Regex("""(?m)^\s*([a-zA-Z_][\w']*)\s*(?:::[^\n]+)?=\s*script\b""")
            .find(file.text)?.groupValues?.getOrNull(1) ?: return null
        return if (module.isNullOrBlank()) script else "$module:$script"
    }
}
