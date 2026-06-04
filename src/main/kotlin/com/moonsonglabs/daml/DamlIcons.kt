package com.moonsonglabs.daml

import com.intellij.openapi.util.IconLoader

object DamlIcons {
    @JvmField
    val File = IconLoader.getIcon("/icons/daml.svg", DamlIcons::class.java)

    @JvmField
    val ToolWindow = IconLoader.getIcon("/icons/daml-toolwindow.svg", DamlIcons::class.java)

    @JvmField
    val WhiteToolWindow = IconLoader.getIcon("/icons/daml-toolwindow-white.svg", DamlIcons::class.java)
}
