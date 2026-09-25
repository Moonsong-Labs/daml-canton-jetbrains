package com.moonsonglabs.daml.scriptresults

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DamlScriptResourceTest {
    @Test
    fun `finds scripts from signatures and definitions`() {
        val text = """
module Main where

setup : Script ()
setup = script do
  pure ()

quickCheck = script do
  pure ()
""".trimIndent()

        assertEquals(
            listOf("setup", "quickCheck"),
            DamlScriptResource.findScripts(text).map { it.name }
        )
    }

    @Test
    fun `selects script around caret`() {
        val text = """
module Main where

first = script do
  pure ()

second = script do
  pure ()
""".trimIndent()

        assertEquals("second", DamlScriptResource.scriptAt(text, text.indexOf("pure ()", text.indexOf("second")))?.name)
    }

    @Test
    fun `returns null when no scripts exist`() {
        assertNull(DamlScriptResource.scriptAt("module Main where\nx = 1", 0))
    }

    @Test
    fun `builds daml compiler virtual resource uri`() {
        assertEquals(
            "daml://compiler?file=%2Ftmp%2FMy%20Project%2FMain.daml&top-level-decl=myScript",
            DamlScriptResource.uri("/tmp/My Project/Main.daml", "myScript")
        )
    }

    @Test
    fun `resolves encoded source path without including declaration or query separators`() {
        val path = "/tmp/My Project/Pool + grant & tests/Pruebaé.daml"
        assertEquals(path, DamlScriptResource.filePath(DamlScriptResource.uri(path, "testOne")))
        assertNull(DamlScriptResource.filePath("daml://compiler?top-level-decl=testOne"))
        assertNull(DamlScriptResource.filePath("daml://compiler?file=%XX"))
        assertNull(DamlScriptResource.filePath("https://example.test/?file=/tmp/Test.daml"))
    }

    @Test
    fun `parses resource title and source through the same encoded reference`() {
        val path = "/tmp/Pool + grant & tests/Pruebaé.daml"
        val name = "testWithApostrophe'"
        val reference = DamlScriptResource.parse(DamlScriptResource.uri(path, name))!!
        assertEquals(path, reference.filePath)
        assertEquals(name, reference.declaration)
        assertEquals("$name - Pruebaé.daml", reference.title)
        assertEquals("Main.daml", DamlScriptResource.parse("daml://compiler?file=/tmp/Main.daml")?.title)
        assertNull(DamlScriptResource.parse("daml://compiler?file=/tmp/Main.daml&top-level-decl=%XX"))
    }
}
