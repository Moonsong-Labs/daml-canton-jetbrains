package com.moonsonglabs.daml.settings

import com.intellij.openapi.fileChooser.FileChooser
import com.intellij.openapi.fileChooser.FileChooserDescriptor
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.TextFieldWithBrowseButton
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.FormBuilder
import com.moonsonglabs.daml.DamlBundle
import com.moonsonglabs.daml.runtime.RuntimeEnvironment
import com.moonsonglabs.daml.sdk.DamlSdkInstaller
import com.moonsonglabs.daml.sdk.DamlSdkVersions
import java.awt.BorderLayout
import javax.swing.JButton
import javax.swing.JComboBox
import javax.swing.JComponent
import javax.swing.JPanel

class DamlSettingsComponent(private val project: Project) {

    val binaryPathField = TextFieldWithBrowseButton().apply {
        toolTipText = DamlBundle.message("daml.settings.binaryPath.tooltip")
        addActionListener {
            val descriptor = FileChooserDescriptor(true, false, false, false, false, false).apply {
                title = "DAML Binary"
                description = DamlBundle.message("daml.settings.binaryPath.tooltip")
            }
            val chosen = FileChooser.chooseFile(descriptor, project, null)
            if (chosen != null) text = chosen.path
        }
    }

    val cantonBinaryPathField = TextFieldWithBrowseButton().apply {
        toolTipText = DamlBundle.message("daml.settings.cantonBinaryPath.tooltip")
        addActionListener {
            val descriptor = FileChooserDescriptor(true, false, false, false, false, false).apply {
                title = "Canton Binary"
                description = DamlBundle.message("daml.settings.cantonBinaryPath.tooltip")
            }
            val chosen = FileChooser.chooseFile(descriptor, project, null)
            if (chosen != null) text = chosen.path
        }
    }

    val dpmBinaryPathField = JBTextField().apply {
        isEditable = false
        toolTipText = DamlBundle.message("daml.settings.dpmBinaryPath.tooltip")
    }

    private val installDpmButton = JButton(DamlBundle.message("daml.settings.installDpm.button")).apply {
        toolTipText = DamlBundle.message("daml.settings.installDpm.tooltip")
        addActionListener {
            runtimeStatusLabel.text = "Installing DPM CLI..."
            DamlSdkInstaller.getInstance(project).installDpmCli { status ->
                runtimeStatusLabel.text = status
                updateDpmBinaryPath()
            }
        }
    }

    private val dpmPanel = JPanel(BorderLayout(6, 0)).apply {
        add(dpmBinaryPathField, BorderLayout.CENTER)
        add(installDpmButton, BorderLayout.EAST)
    }

    val sdkVersionCombo = JComboBox(DamlSdkVersions.choices().toTypedArray()).apply {
        isEditable = true
        toolTipText = DamlBundle.message("daml.settings.sdkVersion.tooltip")
    }

    private val installSdkButton = JButton(DamlBundle.message("daml.settings.installSdk.button")).apply {
        toolTipText = DamlBundle.message("daml.settings.installSdk.tooltip")
        addActionListener {
            runtimeStatusLabel.text = "Installing DAML SDK ${selectedSdkVersion()}..."
            DamlSdkInstaller.getInstance(project).installSelected(
                selectedSdkVersion(),
                binaryPathField.text.trim()
            ) { status ->
                runtimeStatusLabel.text = status
            }
        }
    }

    private val sdkVersionPanel = JPanel(BorderLayout(6, 0)).apply {
        add(sdkVersionCombo, BorderLayout.CENTER)
        add(installSdkButton, BorderLayout.EAST)
    }

    val logLevelCombo = JComboBox(arrayOf("error", "warning", "info", "debug", "trace"))

    val telemetryCombo = JComboBox(arrayOf(
        DamlBundle.message("daml.settings.telemetry.opt-out"),
        DamlBundle.message("daml.settings.telemetry.opt-in"),
        DamlBundle.message("daml.settings.telemetry.ignored")
    ))

    val autorunCheckbox = JBCheckBox(DamlBundle.message("daml.settings.autorunAllTests.label"))

    val extraArgsField = JBTextField().apply {
        toolTipText = DamlBundle.message("daml.settings.extraArguments.tooltip")
    }

    val cantonExtraArgsField = JBTextField().apply {
        toolTipText = DamlBundle.message("daml.settings.cantonExtraArguments.tooltip")
    }

    val runtimeStatusLabel = JBLabel()

    val multiPackageCheckbox = JBCheckBox(DamlBundle.message("daml.settings.multiPackage.label"))

    val panel: JPanel = FormBuilder.createFormBuilder()
        .addLabeledComponent(DamlBundle.message("daml.settings.sdkVersion.label"), sdkVersionPanel, 1, false)
        .addLabeledComponent(DamlBundle.message("daml.settings.runtimeStatus.label"), runtimeStatusLabel, 1, false)
        .addLabeledComponent(DamlBundle.message("daml.settings.binaryPath.label"), binaryPathField, 1, false)
        .addLabeledComponent(DamlBundle.message("daml.settings.cantonBinaryPath.label"), cantonBinaryPathField, 1, false)
        .addLabeledComponent(DamlBundle.message("daml.settings.dpmBinaryPath.label"), dpmPanel, 1, false)
        .addLabeledComponent(DamlBundle.message("daml.settings.logLevel.label"), logLevelCombo, 1, false)
        .addLabeledComponent(DamlBundle.message("daml.settings.telemetry.label"), telemetryCombo, 1, false)
        .addLabeledComponent(DamlBundle.message("daml.settings.extraArguments.label"), extraArgsField, 1, false)
        .addLabeledComponent(DamlBundle.message("daml.settings.cantonExtraArguments.label"), cantonExtraArgsField, 1, false)
        .addComponentFillVertically(JPanel(), 0)
        .panel

    val preferredFocusedComponent: JComponent = binaryPathField

    fun loadFrom(s: DamlProjectSettings) {
        sdkVersionCombo.selectedItem = s.selectedSdkVersion
        runtimeStatusLabel.text = s.lastRuntimeValidation
        binaryPathField.text = s.binaryPath
        cantonBinaryPathField.text = s.cantonBinaryPath
        updateDpmBinaryPath()
        logLevelCombo.selectedItem = s.logLevel
        telemetryCombo.selectedIndex = telemetryToIndex(s.telemetry)
        autorunCheckbox.isSelected = s.autorunAllTests
        extraArgsField.text = s.extraArguments
        cantonExtraArgsField.text = s.cantonExtraArguments
        multiPackageCheckbox.isSelected = s.multiPackageIdeSupport
    }

    fun saveTo(s: DamlProjectSettings) {
        s.selectedSdkVersion = selectedSdkVersion()
        s.binaryPath = binaryPathField.text.trim()
        s.cantonBinaryPath = cantonBinaryPathField.text.trim()
        s.useDPMWhenAvailable = true
        s.logLevel = (logLevelCombo.selectedItem as? String) ?: "info"
        s.telemetry = indexToTelemetry(telemetryCombo.selectedIndex)
        s.autorunAllTests = autorunCheckbox.isSelected
        s.extraArguments = extraArgsField.text
        s.cantonExtraArguments = cantonExtraArgsField.text
        s.multiPackageIdeSupport = multiPackageCheckbox.isSelected
    }

    fun isModified(s: DamlProjectSettings): Boolean =
        selectedSdkVersion() != s.selectedSdkVersion
            || binaryPathField.text.trim() != s.binaryPath
            || cantonBinaryPathField.text.trim() != s.cantonBinaryPath
            || logLevelCombo.selectedItem != s.logLevel
            || indexToTelemetry(telemetryCombo.selectedIndex) != s.telemetry
            || autorunCheckbox.isSelected != s.autorunAllTests
            || extraArgsField.text != s.extraArguments
            || cantonExtraArgsField.text != s.cantonExtraArguments
            || multiPackageCheckbox.isSelected != s.multiPackageIdeSupport

    private fun selectedSdkVersion(): String =
        (sdkVersionCombo.editor.item as? String)?.trim()
            ?.ifBlank { DamlSdkVersions.DEFAULT }
            ?: DamlSdkVersions.DEFAULT

    private fun updateDpmBinaryPath() {
        dpmBinaryPathField.text = RuntimeEnvironment.findExecutable(
            "dpm",
            DamlProjectSettings.getInstance(project)
        )?.toString() ?: "Not found"
    }

    private fun telemetryToIndex(t: String): Int = when (t) {
        "opt-in" -> 1
        "ignored" -> 2
        else -> 0
    }
    private fun indexToTelemetry(i: Int): String = when (i) {
        1 -> "opt-in"
        2 -> "ignored"
        else -> "opt-out"
    }
}
