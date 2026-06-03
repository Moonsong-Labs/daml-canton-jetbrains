package com.moonsonglabs.daml

import com.intellij.lang.Commenter

class DamlCommenter : Commenter {
    override fun getLineCommentPrefix(): String = "-- "
    override fun getBlockCommentPrefix(): String = "{- "
    override fun getBlockCommentSuffix(): String = " -}"
    override fun getCommentedBlockCommentPrefix(): String? = null
    override fun getCommentedBlockCommentSuffix(): String? = null
}
