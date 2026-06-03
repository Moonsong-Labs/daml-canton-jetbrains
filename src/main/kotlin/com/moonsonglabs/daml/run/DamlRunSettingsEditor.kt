package com.moonsonglabs.daml.run

import com.intellij.openapi.options.SettingsEditor
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.FormBuilder
import javax.swing.JComboBox
import javax.swing.JComponent
import javax.swing.JPanel

class DamlRunSettingsEditor : SettingsEditor<DamlRunConfiguration>() {
    private val commandCombo = JComboBox(DamlCommand.entries.toTypedArray())
    private val workspaceField = JBTextField()
    private val fileField = JBTextField()
    private val scriptField = JBTextField()
    private val argsField = JBTextField()

    private val panel: JPanel = FormBuilder.createFormBuilder()
        .addLabeledComponent("Command:", commandCombo)
        .addLabeledComponent("Workspace:", workspaceField)
        .addLabeledComponent("File:", fileField)
        .addLabeledComponent("Script name:", scriptField)
        .addLabeledComponent("Extra arguments:", argsField)
        .addComponentFillVertically(JPanel(), 0)
        .panel

    override fun resetEditorFrom(configuration: DamlRunConfiguration) {
        commandCombo.selectedItem = configuration.command
        workspaceField.text = configuration.workspacePath
        fileField.text = configuration.filePath
        scriptField.text = configuration.scriptName
        argsField.text = configuration.extraArguments
    }

    override fun applyEditorTo(configuration: DamlRunConfiguration) {
        configuration.command = commandCombo.selectedItem as? DamlCommand ?: DamlCommand.BUILD
        configuration.workspacePath = workspaceField.text.trim()
        configuration.filePath = fileField.text.trim()
        configuration.scriptName = scriptField.text.trim()
        configuration.extraArguments = argsField.text
    }

    override fun createEditor(): JComponent = panel
}
