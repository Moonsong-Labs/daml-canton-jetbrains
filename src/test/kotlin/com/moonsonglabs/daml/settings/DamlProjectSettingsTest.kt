package com.moonsonglabs.daml.settings

import org.junit.Assert.assertEquals
import org.junit.Test

class DamlProjectSettingsTest {
    @Test
    fun `normalizes persisted script result views`() {
        assertEquals("contracts", DamlProjectSettings.normalizeScriptResultsView("table"))
        assertEquals("txTree", DamlProjectSettings.normalizeScriptResultsView("transaction"))
        assertEquals("overview", DamlProjectSettings.normalizeScriptResultsView(""))
        assertEquals("overview", DamlProjectSettings.normalizeScriptResultsView(null))
        assertEquals("overview", DamlProjectSettings.normalizeScriptResultsView("unknown"))
        assertEquals("overview", DamlProjectSettings.normalizeScriptResultsView("overview"))
        assertEquals("contracts", DamlProjectSettings.normalizeScriptResultsView("contracts"))
        assertEquals("txTree", DamlProjectSettings.normalizeScriptResultsView("txTree"))
        assertEquals("disclosure", DamlProjectSettings.normalizeScriptResultsView("disclosure"))
        assertEquals("console", DamlProjectSettings.normalizeScriptResultsView("console"))
        assertEquals("raw", DamlProjectSettings.normalizeScriptResultsView("raw"))
    }

    @Test
    fun `loadState migrates old table view`() {
        val settings = DamlProjectSettings()

        settings.loadState(DamlProjectSettings.State(selectedView = "table"))

        assertEquals("contracts", settings.selectedView)
        assertEquals("contracts", settings.getState().selectedView)
    }

    @Test
    fun `loadState migrates old transaction view`() {
        val settings = DamlProjectSettings()

        settings.loadState(DamlProjectSettings.State(selectedView = "transaction"))

        assertEquals("txTree", settings.selectedView)
        assertEquals("txTree", settings.getState().selectedView)
    }

    @Test
    fun `loadState forces dpm preference for sdk 3 workflows`() {
        val settings = DamlProjectSettings()

        settings.loadState(DamlProjectSettings.State(useDPMWhenAvailable = false))
        settings.useDPMWhenAvailable = false

        assertEquals(true, settings.useDPMWhenAvailable)
        assertEquals(true, settings.getState().useDPMWhenAvailable)
    }
}
