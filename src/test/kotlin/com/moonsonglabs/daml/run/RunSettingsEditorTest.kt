package com.moonsonglabs.daml.run

import com.intellij.openapi.options.ConfigurationException
import com.intellij.openapi.util.Disposer
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.util.execution.ParametersListUtil
import com.moonsonglabs.daml.settings.DamlProjectSettings
import java.awt.Component
import java.awt.Container
import javax.swing.JButton
import javax.swing.JTextArea
import javax.swing.JTextField

class RunSettingsEditorTest : BasePlatformTestCase() {
    fun `test failed source discovery does not block a valid manual script configuration`() {
        val configuration = scriptConfiguration()
        val editor = DamlRunSettingsEditor(project)
        Disposer.register(testRootDisposable, editor)
        editor.resetFrom(configuration)
        descendants(editor.component).filterIsInstance<JButton>().single { it.text == "Choose…" }.doClick()
        editor.applyTo(configuration)
        configuration.checkConfiguration()
        assertEquals(SCRIPT_NAME, configuration.scriptName)
        assertEquals(DAR_PATH, configuration.darPath)
    }

    fun `test invalid editor values are rejected without overwriting the saved configuration`() {
        val configuration = scriptConfiguration()
        val editor = DamlRunSettingsEditor(project)
        Disposer.register(testRootDisposable, editor)
        editor.resetFrom(configuration)
        descendants(editor.component).filterIsInstance<JTextField>().single { it.text == SCRIPT_NAME }.text = ""
        try {
            editor.applyTo(configuration)
            fail("A missing script must be rejected")
        } catch (_: ConfigurationException) {
            assertEquals(SCRIPT_NAME, configuration.scriptName)
            assertEquals(DAR_PATH, configuration.darPath)
        }
    }

    fun `test Canton preview matches the executable JAR and global arguments used by Run`() {
        val settings = DamlProjectSettings.getInstance(project)
        val oldBinary = settings.cantonBinaryPath
        val oldArguments = settings.cantonExtraArguments
        val configuration = CantonRunConfiguration(project, CantonRunConfigurationType().configurationFactories.first(), "preview").apply {
            targetPath = "config directory/participant.conf"
            extraArguments = "--log-file-name \"log directory/canton.log\""
        }
        val editor = CantonRunSettingsEditor(project)
        Disposer.register(testRootDisposable, editor)
        try {
            settings.cantonExtraArguments = "--log-level-canton DEBUG"
            for (binary in listOf("/custom directory/canton", "/custom directory/canton.jar")) {
                settings.cantonBinaryPath = binary
                for (mode in CantonMode.entries) {
                    configuration.mode = mode
                    editor.resetFrom(configuration)
                    val preview = descendants(editor.component).filterIsInstance<JTextArea>().single().text
                    assertEquals(configuration.buildCommandLine(), ParametersListUtil.parse(preview))
                }
            }
        } finally {
            settings.cantonBinaryPath = oldBinary
            settings.cantonExtraArguments = oldArguments
        }
    }

    private fun scriptConfiguration() = DamlRunConfiguration(project, DamlRunConfigurationType().configurationFactories.first(), "script").apply {
        command = DamlCommand.SCRIPT
        scriptName = SCRIPT_NAME
        darPath = DAR_PATH
    }

    private fun descendants(component: Component): Sequence<Component> = sequence {
        yield(component)
        if (component is Container) component.components.forEach { yieldAll(descendants(it)) }
    }

    companion object {
        private const val SCRIPT_NAME = "Main:test"
        private const val DAR_PATH = "package.dar"
    }
}
