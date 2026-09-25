package com.moonsonglabs.daml.run

import com.intellij.openapi.options.ConfigurationException
import com.intellij.openapi.options.SettingsEditor
import com.intellij.openapi.project.Project
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.components.JBTextField
import java.awt.BorderLayout
import javax.swing.*

class CantonRunSettingsEditor(project: Project) : SettingsEditor<CantonRunConfiguration>() {
    private val modeCombo = JComboBox(CantonMode.entries.toTypedArray())
    private val workspaceField = pathField(project, "Choose Canton working directory", folder = true)
    private val targetField = pathField(project, "Choose Canton config or script")
    private val argsField = JBTextField()
    private val guidance = JBLabel()
    private val validation = JBLabel()
    private val advanced = JCheckBox("Advanced arguments and command preview")
    private val preview = JBTextArea().apply { isEditable = false; lineWrap = true; wrapStyleWord = true }
    private val advancedPanel = JPanel(BorderLayout()).apply {
        add(formRow("Extra arguments:", argsField), BorderLayout.NORTH); add(preview, BorderLayout.CENTER)
    }
    private val panel = JPanel(BorderLayout()).apply {
        add(JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            add(formRow("Mode:", modeCombo)); add(guidance)
            add(formRow("Workspace:", workspaceField)); add(formRow("File:", targetField)); add(validation)
            add(advanced); add(advancedPanel)
        }, BorderLayout.NORTH)
    }
    init {
        modeCombo.addActionListener { updateForm() }; advanced.addActionListener { updateForm() }
        listOf(targetField.textField, argsField).forEach { it.onChange { updateForm() } }
        updateForm()
    }
    private fun updateForm() {
        val script = modeCombo.selectedItem == CantonMode.SCRIPT
        guidance.text = if (script) "Runs a console script with run <script> and exits." else "Starts Canton with a configuration. Bootstrap is configured separately."
        validation.text = if (targetField.text.isBlank()) "Required: choose a ${if (script) "script" else "configuration"} file." else " "
        advancedPanel.isVisible = advanced.isSelected
        preview.text = "canton ${if (script) "run" else "--config"} \"${targetField.text}\" ${argsField.text}"
        panel.revalidate()
    }
    override fun resetEditorFrom(configuration: CantonRunConfiguration) {
        modeCombo.selectedItem = configuration.mode; workspaceField.text = configuration.workspacePath
        targetField.text = configuration.targetPath; argsField.text = configuration.extraArguments
    }
    override fun applyEditorTo(configuration: CantonRunConfiguration) {
        if (targetField.text.isBlank()) throw ConfigurationException(validation.text)
        configuration.mode = modeCombo.selectedItem as? CantonMode ?: CantonMode.CONFIG
        configuration.workspacePath = workspaceField.text.trim(); configuration.targetPath = targetField.text.trim()
        configuration.extraArguments = argsField.text
    }
    override fun createEditor(): JComponent = panel
}
