package com.moonsonglabs.daml.editor

import com.intellij.codeInsight.hints.InlayInfo
import com.intellij.codeInsight.hints.InlayParameterHintsProvider
import com.intellij.codeInsight.hints.Option
import com.intellij.psi.PsiElement
import com.moonsonglabs.daml.lang.DamlNamedElement
import com.moonsonglabs.daml.lang.DamlSourceModel
import com.moonsonglabs.daml.navigation.DamlModuleResolver

/** Opt-in hints for unambiguous functions applied to simple literal arguments. */
class DamlParameterHints : InlayParameterHintsProvider {
    override fun getDefaultBlackList(): Set<String> = emptySet()
    override fun getSupportedOptions() = listOf(OPTION)
    override fun getParameterHints(element: PsiElement): List<InlayInfo> {
        if (!OPTION.get() || element.firstChild != null) return emptyList()
        val file = element.containingFile ?: return emptyList()
        val reference = DamlModuleResolver.referenceAt(file, element.textOffset) ?: return emptyList()
        val target = DamlModuleResolver.getInstance(element.project).resolveAll(reference, file).singleOrNull() as? DamlNamedElement ?: return emptyList()
        val targetModel = DamlSourceModel.get(target.containingFile)
        val params = targetModel.symbols.filter { it.owner == target.textOffset && it.kind == DamlSourceModel.Kind.PARAMETER }
        if (params.isEmpty()) return emptyList()
        val model = DamlSourceModel.get(file)
        val token = model.tokenAt(element.textOffset) ?: return emptyList()
        val arguments = model.tokens.dropWhile { it.start <= token.start }.takeWhile { it.line == token.line }
        if (arguments.size != params.size || arguments.any { it.text.firstOrNull()?.isDigit() != true && !it.text.startsWith('"') }) return emptyList()
        return arguments.zip(params).map { (argument, param) -> InlayInfo(param.name, argument.start) }
    }
    companion object { private val OPTION = Option("daml.literal.parameter.hints", "Parameter names for literal arguments", false) }
}
