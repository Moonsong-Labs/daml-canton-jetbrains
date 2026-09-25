package com.moonsonglabs.daml.navigation

import com.moonsonglabs.daml.lang.DamlSourceModel

object DamlChoiceNames {
    data class ChoiceDeclaration(val name: String, val startOffset: Int)
    data class ChoiceUse(val name: String, val startOffset: Int, val endOffset: Int)
    fun declarations(text: String): List<ChoiceDeclaration> = DamlSourceModel.parse(text).symbols
        .filter { it.kind == DamlSourceModel.Kind.CHOICE }.map { ChoiceDeclaration(it.name, it.start) }
    fun declarationNamed(text: String, choiceName: String) = declarations(text).firstOrNull { it.name == choiceName.trim('`') }
    fun declarationAt(text: String, offset: Int) = declarations(text).firstOrNull { offset in it.startOffset until it.startOffset + it.name.length }
    fun useAt(text: String, offset: Int) = uses(text).firstOrNull { offset in it.startOffset until it.endOffset }
    fun uses(text: String): List<ChoiceUse> {
        val model = DamlSourceModel.parse(text)
        val tokens = model.tokens.filter { it.code }
        return buildList {
            tokens.forEachIndexed { index, call ->
                if (call.text !in EXERCISE_CALLS) return@forEachIndexed
                var cursor = index + 1
                fun skipAtom(): Boolean {
                    val token = tokens.getOrNull(cursor) ?: return false
                    if (token.text in setOf("(", "[", "{")) {
                        var depth = 0
                        do {
                            val word = tokens.getOrNull(cursor++)?.text ?: return false
                            if (word in setOf("(", "[", "{")) depth++
                            if (word in setOf(")", "]", "}")) depth--
                        } while (depth > 0)
                    } else {
                        cursor++
                        while (tokens.getOrNull(cursor)?.text == ".") cursor += 2
                    }
                    return true
                }
                while (tokens.getOrNull(cursor)?.text == "@") { cursor++; if (!skipAtom()) return@forEachIndexed }
                if (!skipAtom()) return@forEachIndexed
                while (tokens.getOrNull(cursor)?.text in setOf("(", "$")) cursor++
                while (tokens.getOrNull(cursor + 1)?.text == ".") cursor += 2
                val candidate = tokens.getOrNull(cursor) ?: return@forEachIndexed
                if (candidate.line != call.line && model.lines[candidate.line].indent <= model.lines[call.line].indent) return@forEachIndexed
                if (candidate.text.firstOrNull()?.isUpperCase() == true && DamlSourceModel.isName(candidate.text)) add(ChoiceUse(candidate.text, candidate.start, candidate.end))
            }
        }.distinctBy { it.startOffset }
    }
    private val EXERCISE_CALLS = setOf("exercise", "exerciseCmd", "exerciseExactCmd", "exerciseByKey", "exerciseByKeyCmd", "exerciseByKeyExactCmd", "exerciseInterface", "exerciseInterfaceCmd", "exerciseByKeyInterface", "exerciseByKeyInterfaceCmd", "createAndExercise", "createAndExerciseCmd", "createAndExerciseExactCmd", "createAndExerciseWithCidCmd", "createAndExerciseWithCidExactCmd")
}
