package com.moonsonglabs.daml.settings

import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.options.Configurable
import com.intellij.openapi.project.Project
import com.intellij.platform.lsp.api.LspServerManager
import com.moonsonglabs.daml.DamlNotifier
import com.moonsonglabs.daml.lsp.DamlLspServerSupportProvider
import javax.swing.JComponent

class DamlSettingsConfigurable(private val project: Project) : Configurable {

    private var component: DamlSettingsComponent? = null

    override fun getDisplayName(): String = "DAML"

    override fun createComponent(): JComponent {
        val c = DamlSettingsComponent(project)
        component = c
        c.loadFrom(DamlProjectSettings.getInstance(project))
        return c.panel
    }

    override fun isModified(): Boolean =
        component?.isModified(DamlProjectSettings.getInstance(project)) ?: false

    override fun apply() {
        component?.saveTo(DamlProjectSettings.getInstance(project))
        // Restart the LSP so changes (binary path, log level, telemetry, extra args) take
        // effect. Distinguish "no server to restart" (benign) from real failures so a bad
        // binary path or LSP runtime error doesn't disappear silently.
        val mgr = LspServerManager.getInstance(project)
        try {
            mgr.stopAndRestartIfNeeded(DamlLspServerSupportProvider::class.java)
        } catch (t: Throwable) {
            thisLogger().warn("DAML LSP restart on settings apply failed", t)
            DamlNotifier.error(project, "Failed to restart DAML LSP: ${t.message ?: t::class.java.simpleName}")
        }
    }

    override fun reset() {
        component?.loadFrom(DamlProjectSettings.getInstance(project))
    }

    override fun disposeUIResources() {
        component = null
    }

    override fun getPreferredFocusedComponent(): JComponent? = component?.preferredFocusedComponent
}
