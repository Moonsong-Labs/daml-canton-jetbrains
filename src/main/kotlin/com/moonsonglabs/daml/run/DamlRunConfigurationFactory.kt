package com.moonsonglabs.daml.run

import com.intellij.execution.configurations.ConfigurationFactory
import com.intellij.execution.configurations.ConfigurationType
import com.intellij.openapi.project.Project

class DamlRunConfigurationFactory(type: ConfigurationType) : ConfigurationFactory(type) {
    override fun getId(): String = DamlRunConfigurationType.ID

    override fun createTemplateConfiguration(project: Project): DamlRunConfiguration =
        DamlRunConfiguration(project, this, "DAML")
}
