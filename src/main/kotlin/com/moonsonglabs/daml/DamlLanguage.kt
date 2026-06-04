package com.moonsonglabs.daml

import com.intellij.lang.Language

object DamlLanguage : Language("DAML") {
    override fun getDisplayName(): String = "DAML"
    override fun isCaseSensitive(): Boolean = true

    private fun readResolve(): Any = DamlLanguage
}
