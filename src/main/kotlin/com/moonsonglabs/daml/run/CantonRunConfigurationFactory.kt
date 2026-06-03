package com.moonsonglabs.daml.run

import com.intellij.execution.configurations.ConfigurationFactory
import com.intellij.execution.configurations.ConfigurationType
import com.intellij.openapi.project.Project

class CantonRunConfigurationFactory(type: ConfigurationType) : ConfigurationFactory(type) {
    override fun getId(): String = CantonRunConfigurationType.ID

    override fun createTemplateConfiguration(project: Project): CantonRunConfiguration =
        CantonRunConfiguration(project, this, "Canton")
}
