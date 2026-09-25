package com.moonsonglabs.daml.run

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.execution.configurations.RuntimeConfigurationError
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

    fun `test script validation requires a real DAR argument`() {
        val configuration = scriptConfiguration()
        val darOption = DamlRunConfiguration.DAR_OPTION
        for (arguments in listOf("", darOption, "$darOption=", "$darOption --script-name Main:test", "--darwin", "--access-token-file /tmp/token--dar.txt", "-- $darOption package.dar")) {
            configuration.extraArguments = arguments
            try {
                configuration.checkConfiguration()
                fail("Arguments without a DAR must be rejected: $arguments")
            } catch (_: RuntimeConfigurationError) {
                // Expected: these strings contain no DAR option with a value.
            }
        }
        for (arguments in listOf("$darOption \"path with spaces.dar\"", "$darOption=package.dar")) {
            configuration.extraArguments = arguments
            configuration.checkConfiguration()
        }
        configuration.extraArguments = ""
        configuration.darPath = "package.dar"
        configuration.checkConfiguration()
    }

    fun `test script validation includes the global arguments actually executed`() {
        val configuration = scriptConfiguration()
        val settings = DamlProjectSettings.getInstance(project)
        val oldArguments = settings.extraArguments
        try {
            settings.extraArguments = "${DamlRunConfiguration.DAR_OPTION} package.dar"
            configuration.checkConfiguration()
            assertContainsElements(configuration.buildCommandLine(), DamlRunConfiguration.DAR_OPTION, "package.dar")
        } finally {
            settings.extraArguments = oldArguments
        }
    }

    private fun scriptConfiguration() = DamlRunConfiguration(project, DamlRunConfigurationType().configurationFactories.first(), "script").apply {
        command = DamlCommand.SCRIPT
        scriptName = "Main:test"
    }
}
