package com.moonsonglabs.daml

import com.intellij.extapi.psi.PsiFileBase
import com.intellij.openapi.fileTypes.FileType
import com.intellij.psi.FileViewProvider

class DamlPsiFile(viewProvider: FileViewProvider) : PsiFileBase(viewProvider, DamlLanguage) {
    override fun getFileType(): FileType = DamlFileType
    override fun toString(): String = "DAML File"
}
