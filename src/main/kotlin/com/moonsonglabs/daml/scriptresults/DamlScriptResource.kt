package com.moonsonglabs.daml.scriptresults

import java.net.URLEncoder
import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets

object DamlScriptResource {
    private const val SCHEME = "daml"
    private const val HOST = "compiler"
    private const val FILE_PARAMETER = "file"
    private const val DECLARATION_PARAMETER = "top-level-decl"
    private val definitionRegex = Regex("""(?m)^\s*([A-Za-z_][\w']*)\s*=\s*script\b""")
    private val signatureRegex = Regex("""(?m)^\s*([A-Za-z_][\w']*)\s*:\s*(?:[^\n]*=>\s*)?Script\b""")

    data class ScriptDefinition(val name: String, val startOffset: Int)

    data class Reference(val filePath: String, val declaration: String?) {
        val title: String
            get() = listOfNotNull(declaration, filePath.substringAfterLast('/')).joinToString(" - ")
    }

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
        }?.value ?: scripts.lastOrNull { it.startOffset <= clampedOffset }
    }

    fun qualifiedName(text: String, script: ScriptDefinition): String {
        val module = Regex("(?m)^\\s*module\\s+([A-Za-z0-9_.']+)\\s+where\\b").find(text)?.groupValues?.get(1)
        return if (module.isNullOrBlank()) script.name else "$module:${script.name}"
    }

    fun title(scriptName: String): String = "Script: $scriptName"

    fun uri(filePath: String, scriptName: String): String =
        "$SCHEME://$HOST?$FILE_PARAMETER=${queryValue(filePath)}&$DECLARATION_PARAMETER=${queryValue(scriptName)}"

    fun filePath(uri: String): String? = parse(uri)?.filePath

    fun parse(uri: String): Reference? = runCatching {
        val resource = URI(uri)
        if (resource.scheme != SCHEME || resource.host != HOST) return null
        val parameters = resource.rawQuery.orEmpty().split('&').mapNotNull { parameter ->
            val pair = parameter.split('=', limit = 2)
            if (pair.size != 2) return@mapNotNull null
            URLDecoder.decode(pair[0], StandardCharsets.UTF_8) to URLDecoder.decode(pair[1], StandardCharsets.UTF_8)
        }.toMap()
        val path = parameters[FILE_PARAMETER]?.takeIf(String::isNotBlank) ?: return null
        Reference(path, parameters[DECLARATION_PARAMETER]?.takeIf(String::isNotBlank))
    }.getOrNull()

    private fun queryValue(value: String): String =
        URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20")
}
