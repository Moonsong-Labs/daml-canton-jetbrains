package com.moonsonglabs.daml.canton

import com.intellij.lang.Language

object CantonLanguage : Language("Canton") {
    override fun getDisplayName(): String = "Canton"
    override fun isCaseSensitive(): Boolean = true
    private fun readResolve(): Any = CantonLanguage
}
