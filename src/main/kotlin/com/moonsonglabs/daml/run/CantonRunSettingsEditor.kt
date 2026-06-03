package com.moonsonglabs.daml.run

import com.intellij.openapi.options.SettingsEditor
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.FormBuilder
import javax.swing.JComboBox
import javax.swing.JComponent
import javax.swing.JPanel

class CantonRunSettingsEditor : SettingsEditor<CantonRunConfiguration>() {
    private val modeCombo = JComboBox(CantonMode.entries.toTypedArray())
    private val workspaceField = JBTextField()
    private val targetField = JBTextField()
    private val argsField = JBTextField()

    private val panel: JPanel = FormBuilder.createFormBuilder()
        .addLabeledComponent("Mode:", modeCombo)
        .addLabeledComponent("Workspace:", workspaceField)
        .addLabeledComponent("Config/script file:", targetField)
        .addLabeledComponent("Extra arguments:", argsField)
        .addComponentFillVertically(JPanel(), 0)
        .panel

    override fun resetEditorFrom(configuration: CantonRunConfiguration) {
        modeCombo.selectedItem = configuration.mode
        workspaceField.text = configuration.workspacePath
        targetField.text = configuration.targetPath
        argsField.text = configuration.extraArguments
    }

    override fun applyEditorTo(configuration: CantonRunConfiguration) {
        configuration.mode = modeCombo.selectedItem as? CantonMode ?: CantonMode.CONFIG
        configuration.workspacePath = workspaceField.text.trim()
        configuration.targetPath = targetField.text.trim()
        configuration.extraArguments = argsField.text
    }

    override fun createEditor(): JComponent = panel
}
