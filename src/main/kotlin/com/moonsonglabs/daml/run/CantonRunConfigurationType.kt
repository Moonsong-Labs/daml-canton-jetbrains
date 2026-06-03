package com.moonsonglabs.daml.run

import com.intellij.execution.configurations.ConfigurationTypeBase
import com.moonsonglabs.daml.DamlIcons

class CantonRunConfigurationType : ConfigurationTypeBase(
    ID,
    "Canton",
    "Run Canton config and console scripts",
    DamlIcons.ToolWindow
) {
    init {
        addFactory(CantonRunConfigurationFactory(this))
    }

    companion object {
        const val ID = "CANTON_RUN_CONFIGURATION"
    }
}
