package com.moonsonglabs.daml

import com.intellij.openapi.fileTypes.LanguageFileType
import javax.swing.Icon

// `fieldName="INSTANCE"` in plugin.xml refers to the static field that Kotlin generates
// automatically for an `object` declaration. No manual @JvmField needed (and it would
// clash with the auto-generated one).
object DamlFileType : LanguageFileType(DamlLanguage) {
    override fun getName(): String = "DAML"
    override fun getDescription(): String = "DAML smart-contract source"
    override fun getDefaultExtension(): String = "daml"
    override fun getIcon(): Icon = DamlIcons.File
}
