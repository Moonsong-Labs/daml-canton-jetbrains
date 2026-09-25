package com.moonsonglabs.daml.editor

import com.intellij.openapi.components.service
import com.intellij.openapi.util.text.StringUtil
import com.moonsonglabs.daml.lang.DamlNamedElement
import com.moonsonglabs.daml.lang.DamlSourceModel
import com.moonsonglabs.daml.lang.DamlSourceModel.Kind
import com.moonsonglabs.daml.lang.DamlSourceModel.Symbol
import com.moonsonglabs.daml.navigation.DamlPackageSources

/** Source documentation only: absent annotations are left to the compiler, never inferred here. */
internal object DamlDocumentation {
    private val structural = setOf(Kind.TEMPLATE, Kind.CHOICE, Kind.INTERFACE, Kind.DATA, Kind.NEWTYPE, Kind.TYPE, Kind.CLASS, Kind.EXCEPTION)
    private val callable = setOf(Kind.FUNCTION, Kind.METHOD, Kind.VALUE)
    private val quantifiers = Regex("^forall\\s+[^.]+\\.\\s*")

    fun hasDetails(target: DamlNamedElement): Boolean {
        val symbol = target.symbol ?: return false
        val model = DamlSourceModel.get(target.containingFile)
        return symbol.kind in structural || explicitType(model, symbol) != null || comment(model, symbol).isNotBlank()
    }

    fun render(target: DamlNamedElement): String? {
        val symbol = target.symbol ?: return null
        val model = DamlSourceModel.get(target.containingFile)
        val type = explicitType(model, symbol)
        val fields = model.symbols.filter { it.owner == symbol.start && it.kind == Kind.FIELD }
        val doc = comment(model, symbol)
        val provenance = target.containingFile.virtualFile?.let { target.project.service<DamlPackageSources>().provenance(it) }.orEmpty()
        return buildString {
            append("<div class='definition'><pre>")
            if (symbol.kind in structural) append(escape(symbol.kind.name.lowercase())).append(" ")
            append(escape(symbol.name))
            if (type != null) append(" : ").append(escape(type))
            else if (symbol.kind in setOf(Kind.DATA, Kind.NEWTYPE, Kind.TYPE, Kind.CLASS)) {
                val rest = model.lineAt(symbol.start).tokens.filter { it.start > symbol.start }
                if (rest.isNotEmpty()) append(" ").append(escape(source(rest)))
            }
            append("</pre></div><div class='content'>")
            paragraph(doc)
            model.owner(symbol)?.let { paragraph("Owner: ${it.name}") }
            if (symbol.kind == Kind.CHOICE) {
                val mode = model.lineAt(symbol.start).tokens.firstOrNull { it.text.endsWith("consuming") }?.text ?: "consuming"
                paragraph("$mode choice")
            }
            if (fields.isNotEmpty() || symbol.kind in setOf(Kind.TEMPLATE, Kind.CHOICE)) {
                heading(if (symbol.kind in setOf(Kind.TEMPLATE, Kind.CHOICE)) "Inputs" else "Fields")
                if (fields.isEmpty()) paragraph("None")
                fields.forEach { field ->
                    code(field.name + (explicitType(model, field)?.let { " : $it" } ?: ""))
                    paragraph(comment(model, field))
                }
            }
            when {
                symbol.kind == Kind.TEMPLATE -> {
                    heading("Creation result")
                    code("ContractId ${symbol.name}")
                }
                symbol.kind == Kind.CHOICE && type != null -> {
                    heading("Returns")
                    code(type)
                }
                symbol.kind in callable && type != null -> {
                    // Constraints belong to the signature, not to the first parameter type.
                    val unconstrained = splitTopLevel(type.replaceFirst(quantifiers, ""), "=>").last()
                    val parts = splitTopLevel(unconstrained, "->")
                    if (parts.size > 1) {
                        heading("Inputs")
                        val names = parameterNames(model, symbol, parts.size - 1)
                        parts.dropLast(1).forEachIndexed { index, input -> code("${names.getOrNull(index) ?: "Argument ${index + 1}"} : $input") }
                    }
                    heading(if (parts.size > 1) "Returns" else "Type")
                    code(parts.last())
                }
            }
            append("</div><div class='bottom'>").append(escape(model.module.orEmpty()))
            if (provenance.isNotBlank()) append("<br>").append(escape(provenance))
            append("</div>")
        }
    }

    private fun explicitType(model: DamlSourceModel, symbol: Symbol): String? {
        if (symbol.kind !in callable && symbol.kind !in setOf(Kind.CHOICE, Kind.FIELD)) return null
        return source(model.explicitTypeTokens(symbol.start)).trim().takeIf { it.isNotEmpty() }
    }

    private fun source(tokens: List<DamlSourceModel.Token>): String = buildString {
        tokens.forEachIndexed { index, token ->
            if (index > 0 && token.start > tokens[index - 1].end) append(" ")
            append(token.text)
        }
    }

    private fun parameterNames(model: DamlSourceModel, symbol: Symbol, arity: Int): List<String> {
        val definition = (listOf(symbol.start) + symbol.aliases).map(model::lineAt).firstOrNull { line -> line.tokens.any { it.text == "=" } } ?: return emptyList()
        val tokens = definition.tokens.dropWhile { it.text != symbol.name }.drop(1).takeWhile { it.text != "=" }
        // Patterns and partial definitions cannot safely label positions in the full signature.
        return if (tokens.size == arity && tokens.all { DamlSourceModel.isLowerName(it.text) }) tokens.map { it.text } else emptyList()
    }

    private fun splitTopLevel(type: String, separator: String): List<String> {
        var depth = 0
        var start = 0
        var at = 0
        val parts = mutableListOf<String>()
        while (at < type.length) {
            when (type[at]) { '(', '[', '{' -> depth++; ')', ']', '}' -> depth-- }
            if (depth == 0 && type.startsWith(separator, at)) {
                parts += type.substring(start, at).trim()
                at += separator.length
                start = at
            } else at++
        }
        parts += type.substring(start).trim()
        return parts
    }

    private fun comment(model: DamlSourceModel, symbol: Symbol): String {
        val before = model.tokens.takeWhile { it.end <= symbol.header }
        val preceding = before.takeLastWhile { !it.code && (it.text.startsWith("--") || it.text.startsWith("{-")) }
        // Require standalone comments; a trailing comment on another declaration belongs to it.
        val standalone = preceding.takeLastWhile { token ->
            model.text.substring(model.text.lastIndexOf('\n', token.start - 1) + 1, token.start).isBlank()
        }
        val block = standalone.lastOrNull()?.takeIf { it.text.startsWith("{-|") || it.text.startsWith("{- |") }
        if (block != null) return block.text.removePrefix("{-").trimStart().removePrefix("|").removeSuffix("-}").trimIndent().trim()
        val lines = standalone.takeLastWhile { it.text.startsWith("--") && !it.text.startsWith("-- ^") }
        val marker = lines.indexOfLast { it.text.removePrefix("--").trimStart().startsWith("|") }
        if (lines.isNotEmpty()) return lines.drop(marker.coerceAtLeast(0)).joinToString("\n") {
            it.text.removePrefix("--").trimStart().removePrefix("|").trimStart()
        }
        // Haddock's trailing field documentation is attached to this declaration only.
        val line = model.lineAt(symbol.start)
        return model.tokens.firstOrNull { it.start > symbol.start && it.start <= line.end &&
            it.text.startsWith("--") && it.text.removePrefix("--").trimStart().startsWith("^") }
            ?.text?.removePrefix("--")?.trimStart()?.removePrefix("^")?.trimStart().orEmpty()
    }

    private fun escape(value: String) = StringUtil.escapeXmlEntities(value)
    private fun StringBuilder.paragraph(text: String) {
        if (text.isNotBlank()) append("<p>").append(escape(text).replace("\n", "<br>")).append("</p>")
    }
    private fun StringBuilder.heading(text: String) { append("<h3>").append(escape(text)).append("</h3>") }
    private fun StringBuilder.code(text: String) { append("<p><code>").append(escape(text)).append("</code></p>") }
}
