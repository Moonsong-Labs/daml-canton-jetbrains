package com.moonsonglabs.daml.settings

import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.project.Project
import com.intellij.openapi.components.service

@State(
    name = "DamlProjectSettings",
    storages = [Storage("daml.xml")]
)
@Service(Service.Level.PROJECT)
class DamlProjectSettings : PersistentStateComponent<DamlProjectSettings.State> {

    data class State(
        var binaryPath: String = "",
        var selectedSdkVersion: String = "3.4.11",
        var useDPMWhenAvailable: Boolean = true,
        var logLevel: String = "info",
        var telemetry: String = "opt-out",
        var autorunAllTests: Boolean = false,
        var extraArguments: String = "",
        var cantonBinaryPath: String = "",
        var cantonExtraArguments: String = "",
        var lastRuntimeValidation: String = "Not validated",
        var multiPackageIdeSupport: Boolean = false,
        var showArchived: Boolean = false,
        var showDetailedDisclosure: Boolean = false,
        var selectedView: String = "overview"
    )

    private var state = State()

    override fun getState(): State = state
    override fun loadState(state: State) {
        state.selectedView = normalizeScriptResultsView(state.selectedView)
        state.useDPMWhenAvailable = true
        this.state = state
    }

    var binaryPath: String
        get() = state.binaryPath
        set(value) { state.binaryPath = value }
    var selectedSdkVersion: String
        get() = state.selectedSdkVersion
        set(value) { state.selectedSdkVersion = value.ifBlank { "3.4.11" } }
    var useDPMWhenAvailable: Boolean
        get() = true
        set(value) { state.useDPMWhenAvailable = true }
    var logLevel: String
        get() = state.logLevel
        set(value) { state.logLevel = value }
    var telemetry: String
        get() = state.telemetry
        set(value) { state.telemetry = value }
    var autorunAllTests: Boolean
        get() = state.autorunAllTests
        set(value) { state.autorunAllTests = value }
    var extraArguments: String
        get() = state.extraArguments
        set(value) { state.extraArguments = value }
    var cantonBinaryPath: String
        get() = state.cantonBinaryPath
        set(value) { state.cantonBinaryPath = value }
    var cantonExtraArguments: String
        get() = state.cantonExtraArguments
        set(value) { state.cantonExtraArguments = value }
    var lastRuntimeValidation: String
        get() = state.lastRuntimeValidation
        set(value) { state.lastRuntimeValidation = value }
    var multiPackageIdeSupport: Boolean
        get() = state.multiPackageIdeSupport
        set(value) { state.multiPackageIdeSupport = value }
    var showArchived: Boolean
        get() = state.showArchived
        set(value) { state.showArchived = value }
    var showDetailedDisclosure: Boolean
        get() = state.showDetailedDisclosure
        set(value) { state.showDetailedDisclosure = value }
    var selectedView: String
        get() = normalizeScriptResultsView(state.selectedView)
        set(value) { state.selectedView = normalizeScriptResultsView(value) }

    companion object {
        private val scriptResultsViews = setOf("overview", "contracts", "txTree", "disclosure", "console", "raw")

        fun normalizeScriptResultsView(value: String?): String = when {
            value == "table" -> "contracts"
            value == "transaction" -> "txTree"
            value != null && value in scriptResultsViews -> value
            else -> "overview"
        }

        fun getInstance(project: Project): DamlProjectSettings = project.service()
    }
}
