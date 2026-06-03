package com.moonsonglabs.daml.snippets

import com.intellij.codeInsight.template.TemplateActionContext
import com.intellij.codeInsight.template.TemplateContextType
import com.moonsonglabs.daml.DamlFileType

/**
 * Activates DAML live templates inside `.daml` files. The contextId "DAML" matches
 * `<option name="DAML" value="true"/>` in `resources/snippets/daml-snippets.xml`.
 */
class DamlTemplateContextType : TemplateContextType("DAML") {
    override fun isInContext(context: TemplateActionContext): Boolean =
        context.file.fileType === DamlFileType
}
