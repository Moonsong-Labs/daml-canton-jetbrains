package com.moonsonglabs.daml.canton

import com.intellij.psi.tree.IElementType

class CantonTokenType(debugName: String) : IElementType(debugName, CantonLanguage)

object CantonTokenTypes {
    @JvmField val WHITE_SPACE = CantonTokenType("WHITE_SPACE")
    @JvmField val COMMENT = CantonTokenType("COMMENT")
    @JvmField val KEYWORD = CantonTokenType("KEYWORD")
    @JvmField val IDENTIFIER = CantonTokenType("IDENTIFIER")
    @JvmField val STRING = CantonTokenType("STRING")
    @JvmField val NUMBER = CantonTokenType("NUMBER")
    @JvmField val BRACE = CantonTokenType("BRACE")
    @JvmField val BRACKET = CantonTokenType("BRACKET")
    @JvmField val OPERATOR = CantonTokenType("OPERATOR")
    @JvmField val BAD_CHARACTER = CantonTokenType("BAD_CHARACTER")

    val keywords = setOf(
        "canton", "participants", "participant", "domains", "domain", "synchronizers",
        "synchronizer", "sequencer", "mediator", "storage", "ledger-api", "admin-api",
        "remote", "local", "bootstrap", "import", "include", "true", "false", "null"
    )
}
