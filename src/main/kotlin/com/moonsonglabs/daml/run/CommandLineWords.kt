package com.moonsonglabs.daml.run

object CommandLineWords {
    fun split(value: String): List<String> {
        if (value.isBlank()) return emptyList()
        val result = mutableListOf<String>()
        val current = StringBuilder()
        var quote: Char? = null
        var escaped = false
        for (c in value) {
            when {
                escaped -> {
                    current.append(c)
                    escaped = false
                }
                c == '\\' -> escaped = true
                quote != null && c == quote -> quote = null
                quote != null -> current.append(c)
                c == '\'' || c == '"' -> quote = c
                c.isWhitespace() -> {
                    if (current.isNotEmpty()) {
                        result += current.toString()
                        current.clear()
                    }
                }
                else -> current.append(c)
            }
        }
        if (current.isNotEmpty()) result += current.toString()
        return result
    }
}
