package com.moonsonglabs.daml

import com.intellij.AbstractBundle
import org.jetbrains.annotations.PropertyKey

private const val BUNDLE_FQN = "messages.DamlBundle"

object DamlBundle : AbstractBundle(BUNDLE_FQN) {
    fun message(@PropertyKey(resourceBundle = BUNDLE_FQN) key: String, vararg params: Any): String =
        getMessage(key, *params)
}
