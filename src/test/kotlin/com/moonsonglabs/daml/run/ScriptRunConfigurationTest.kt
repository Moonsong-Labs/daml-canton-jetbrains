package com.moonsonglabs.daml.run

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.moonsonglabs.daml.scriptresults.DamlScriptResource
import com.moonsonglabs.daml.settings.DamlProjectSettings

class ScriptRunConfigurationTest : BasePlatformTestCase() {
    fun `test canton script uses run while config uses config flag`() {
        val factory = CantonRunConfigurationType().configurationFactories.first()
        val configuration = CantonRunConfiguration(project, factory, "script")
        DamlProjectSettings.getInstance(project).cantonBinaryPath = "canton"
        configuration.targetPath = "example.canton"; configuration.mode = CantonMode.SCRIPT
        assertEquals(listOf("canton", "run", "example.canton"), configuration.buildCommandLine())
        configuration.mode = CantonMode.CONFIG; configuration.targetPath = "canton.conf"
        assertEquals(listOf("canton", "--config", "canton.conf"), configuration.buildCommandLine())
    }

    fun `test shared script discovery selects signature based script at caret without falling back to first`() {
        val source = "module Main where\n\nfirst : Script ()\nfirst = script do\n  pure ()\n\nsecond : Script ()\nsecond = script do\n  pure ()"
        assertNull(DamlScriptResource.scriptAt(source, 0))
        val selected = DamlScriptResource.scriptAt(source, source.lastIndexOf("pure"))!!
        assertEquals("Main:second", DamlScriptResource.qualifiedName(source, selected))
    }
}
