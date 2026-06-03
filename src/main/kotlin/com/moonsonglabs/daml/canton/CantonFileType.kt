package com.moonsonglabs.daml.canton

import com.intellij.openapi.fileTypes.LanguageFileType
import com.moonsonglabs.daml.DamlIcons
import javax.swing.Icon

object CantonFileType : LanguageFileType(CantonLanguage) {
    override fun getName(): String = "Canton"
    override fun getDescription(): String = "Canton config or console script"
    override fun getDefaultExtension(): String = "conf"
    override fun getIcon(): Icon = DamlIcons.ToolWindow
}
