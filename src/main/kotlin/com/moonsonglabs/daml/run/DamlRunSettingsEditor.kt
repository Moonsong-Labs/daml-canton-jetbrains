package com.moonsonglabs.daml.run

import com.intellij.openapi.options.ConfigurationException
import com.intellij.openapi.options.SettingsEditor
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.components.JBTextField
import com.moonsonglabs.daml.scriptresults.DamlScriptResource
import java.awt.BorderLayout
import java.nio.file.Files
import java.nio.file.Path
import javax.swing.*

class DamlRunSettingsEditor(private val project: Project) : SettingsEditor<DamlRunConfiguration>() {
    private val commandCombo = JComboBox(DamlCommand.entries.toTypedArray())
    private val workspaceField = pathField(project, "Choose Daml workspace", folder = true)
    private val fileField = pathField(project, "Choose Daml source file")
    private val darField = pathField(project, "Choose compiled DAR")
    private val scriptField = JBTextField()
    private val argsField = JBTextField()
    private val validation = JBLabel()
    private val guidance = JBLabel()
    private val advanced = JCheckBox("Advanced arguments and command preview")
    private val preview = JBTextArea().apply { isEditable = false; lineWrap = true; wrapStyleWord = true }
    private val fileRow = formRow("Source file:", fileField)
    private val darRow = formRow("DAR:", darField)
    private val scriptRow = formRow("Script:", JPanel(BorderLayout(6, 0)).apply {
        add(scriptField, BorderLayout.CENTER)
        add(JButton("Choose…").apply { addActionListener { chooseScript() } }, BorderLayout.EAST)
    })
    private val advancedPanel = JPanel().apply {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        add(formRow("Extra arguments:", argsField)); add(preview)
    }
    private val panel = JPanel(BorderLayout()).apply {
        add(JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            add(formRow("Command:", commandCombo)); add(formRow("Workspace:", workspaceField))
            add(guidance); add(fileRow); add(darRow); add(scriptRow); add(validation)
            add(advanced); add(advancedPanel)
        }, BorderLayout.NORTH)
    }
    private var configuration: DamlRunConfiguration? = null

    init {
        commandCombo.renderer = DefaultListCellRenderer().apply { }
        commandCombo.addActionListener { updateForm() }
        advanced.addActionListener { updateForm() }
        listOf(workspaceField.textField, fileField.textField, darField.textField, scriptField, argsField).forEach { it.onChange { updateForm() } }
        updateForm()
    }

    private fun chooseScript() {
        val text = runCatching { Files.readString(Path.of(fileField.text)) }.getOrNull()
        if (text == null) { validation.text = "Choose a Daml source file to discover scripts."; return }
        val scripts = DamlScriptResource.findScripts(text).map { DamlScriptResource.qualifiedName(text, it) }
        if (scripts.isEmpty()) { validation.text = "No script declarations found in this file."; return }
        Messages.showEditableChooseDialog("Select the script to run through the CLI.", "CLI Run Script", null,
            scripts.toTypedArray(), scripts.first(), null)?.let { scriptField.text = it }
    }

    private fun updateForm() {
        val command = commandCombo.selectedItem as? DamlCommand ?: DamlCommand.BUILD
        val script = command == DamlCommand.SCRIPT
        fileRow.isVisible = script || command == DamlCommand.TEST
        darRow.isVisible = script; scriptRow.isVisible = script
        advancedPanel.isVisible = advanced.isSelected
        guidance.text = if (script) "CLI Run Script uses a compiled DAR. Set ledger connection arguments under Advanced."
            else "Runs the selected command in your Daml workspace."
        validation.text = when {
            script && scriptField.text.isBlank() -> "Required: choose a script (Module:script)."
            script && darField.text.isBlank() && !argsField.text.contains("--dar") -> "Required: choose a compiled DAR."
            else -> " "
        }
        configuration?.let { original ->
            val copy = original.clone() as DamlRunConfiguration
            copyValues(copy)
            preview.text = runCatching { copy.buildCommandLine().joinToString(" ") { if (it.contains(' ')) "\"$it\"" else it } }
                .getOrElse { "Command preview: ${it.message}" }
        }
        panel.revalidate()
    }

    private fun copyValues(configuration: DamlRunConfiguration) {
        configuration.command = commandCombo.selectedItem as? DamlCommand ?: DamlCommand.BUILD
        configuration.workspacePath = workspaceField.text.trim()
        configuration.filePath = fileField.text.trim()
        configuration.darPath = darField.text.trim()
        configuration.scriptName = scriptField.text.trim()
        configuration.extraArguments = argsField.text
    }

    override fun resetEditorFrom(configuration: DamlRunConfiguration) {
        this.configuration = configuration
        commandCombo.selectedItem = configuration.command
        workspaceField.text = configuration.workspacePath; fileField.text = configuration.filePath
        darField.text = configuration.darPath; scriptField.text = configuration.scriptName; argsField.text = configuration.extraArguments
        updateForm()
    }

    override fun applyEditorTo(configuration: DamlRunConfiguration) {
        copyValues(configuration)
        if (configuration.command == DamlCommand.SCRIPT && validation.text.isNotBlank()) throw ConfigurationException(validation.text)
    }

    override fun createEditor(): JComponent = panel
}
