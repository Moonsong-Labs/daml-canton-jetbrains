package com.moonsonglabs.daml.run

import com.intellij.execution.configurations.ConfigurationTypeBase
import com.moonsonglabs.daml.DamlIcons

class DamlRunConfigurationType : ConfigurationTypeBase(
    ID,
    "DAML",
    "Run DAML build, test, script, or start commands",
    DamlIcons.File
) {
    init {
        addFactory(DamlRunConfigurationFactory(this))
    }

    companion object {
        const val ID = "DAML_RUN_CONFIGURATION"
    }
}
