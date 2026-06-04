package com.moonsonglabs.daml.scriptresults

import org.junit.Assert.assertTrue
import org.junit.Test

class WebviewResourceTest {
    @Test
    fun `webview declares script result explorer tabs`() {
        val html = resource("/webview/webview.html")
        val js = resource("/webview/webview.js")

        assertTrue(html.contains("id=\"view_tabs\""))
        assertTrue(html.contains("id=\"search_input\""))
        assertTrue(html.contains("id=\"progress_status\""))
        assertTrue(html.contains("\$webviewTheme"))
        listOf("overview", "contracts", "txTree", "disclosure", "console", "raw").forEach { view ->
            assertTrue("Expected $view tab in webview script", js.contains("id: '$view'"))
        }
    }

    @Test
    fun `webview keeps bridge commands and raw fallback wired`() {
        val js = resource("/webview/webview.js")

        assertTrue(js.contains("sanitizeToFragment"))
        assertTrue(js.contains("command:daml.revealLocation"))
        assertTrue(js.contains("reveal_location"))
        assertTrue(js.contains("set_show_archived"))
        assertTrue(js.contains("set_show_detailed_disclosure"))
        assertTrue(js.contains("set_selected_view"))
        assertTrue(js.contains("set_progress"))
        assertTrue(js.contains("ide-dark"))
        assertTrue(js.contains("Raw Sanitized Script Results"))
    }

    @Test
    fun `representative script result fixtures are checked in`() {
        listOf(
            "/webview/fixtures/active-contracts.html",
            "/webview/fixtures/archived-disclosure.html",
            "/webview/fixtures/nested-transaction.html",
            "/webview/fixtures/notes-and-source-links.html",
            "/webview/fixtures/hostile.html",
            "/webview/fixtures/malformed.html"
        ).forEach { fixture ->
            assertTrue("Expected non-empty fixture $fixture", resource(fixture).isNotBlank())
        }
    }

    private fun resource(path: String): String =
        javaClass.getResource(path)?.readText()
            ?: error("Missing test resource $path")
}
