package com.moonsonglabs.daml.scriptresults

import java.net.URLEncoder
import java.nio.charset.StandardCharsets

object DamlScriptResource {
    private val definitionRegex = Regex("""(?m)^\s*([A-Za-z_][\w']*)\s*=\s*script\b""")
    private val signatureRegex = Regex("""(?m)^\s*([A-Za-z_][\w']*)\s*:\s*(?:[^\n]*=>\s*)?Script\b""")

    data class ScriptDefinition(val name: String, val startOffset: Int)

    fun findScripts(text: String): List<ScriptDefinition> {
        val offsetsByName = linkedMapOf<String, Int>()
        for (match in signatureRegex.findAll(text)) {
            val name = match.groups[1] ?: continue
            offsetsByName.putIfAbsent(name.value, name.range.first)
        }
        for (match in definitionRegex.findAll(text)) {
            val name = match.groups[1] ?: continue
            offsetsByName.merge(name.value, name.range.first, ::minOf)
        }
        return offsetsByName
            .map { (name, offset) -> ScriptDefinition(name, offset) }
            .sortedBy { it.startOffset }
    }

    fun scriptAt(text: String, offset: Int): ScriptDefinition? {
        val scripts = findScripts(text)
        if (scripts.isEmpty()) return null
        val clampedOffset = offset.coerceIn(0, text.length)
        return scripts.withIndex().firstOrNull { (index, script) ->
            val nextStart = scripts.getOrNull(index + 1)?.startOffset ?: text.length + 1
            clampedOffset in script.startOffset until nextStart
        }?.value ?: scripts.lastOrNull { it.startOffset <= clampedOffset } ?: scripts.first()
    }

    fun title(scriptName: String): String = "Script: $scriptName"

    fun uri(filePath: String, scriptName: String): String =
        "daml://compiler?file=${queryValue(filePath)}&top-level-decl=${queryValue(scriptName)}"

    private fun queryValue(value: String): String =
        URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20")
}
