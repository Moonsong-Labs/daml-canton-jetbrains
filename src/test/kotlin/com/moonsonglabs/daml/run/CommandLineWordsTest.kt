package com.moonsonglabs.daml.run

import org.junit.Assert.assertEquals
import org.junit.Test

class CommandLineWordsTest {
    @Test
    fun splitsQuotedArguments() {
        assertEquals(
            listOf("--ledger-host", "localhost", "--flag=hello world", "--name", "Alice"),
            CommandLineWords.split("""--ledger-host localhost "--flag=hello world" --name Alice""")
        )
    }

    @Test
    fun keepsEmptyInputEmpty() {
        assertEquals(emptyList<String>(), CommandLineWords.split("   "))
    }
}
