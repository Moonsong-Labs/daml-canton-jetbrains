package com.moonsonglabs.daml.scriptresults

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.util.concurrent.TimeUnit

class WebviewJavascriptTest {
    @Test
    fun `webview helper tests pass under node`() {
        val node = findExecutable("node")
        if (node == null) {
            assumeTrue("Node.js is unavailable; skipping webview JavaScript helper tests.", false)
            return
        }
        val script = File("src/test/webview/webview-model-test.js")
        assertTrue("Missing ${script.path}", script.isFile)

        val process = ProcessBuilder(node, script.absolutePath)
            .directory(File("."))
            .redirectErrorStream(true)
            .start()
        val output = process.inputStream.bufferedReader().readText()
        val finished = process.waitFor(10, TimeUnit.SECONDS)

        assertTrue("Timed out running webview JavaScript tests. Output:\n$output", finished)
        assertEquals(output, 0, process.exitValue())
    }

    private fun findExecutable(name: String): String? =
        System.getenv("PATH")
            ?.split(File.pathSeparator)
            ?.asSequence()
            ?.map { File(it, name) }
            ?.firstOrNull { it.canExecute() }
            ?.absolutePath
}
