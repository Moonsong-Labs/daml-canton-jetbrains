package com.moonsonglabs.daml.structure

import com.intellij.icons.AllIcons
import com.intellij.ide.projectView.PresentationData
import com.intellij.ide.structureView.StructureViewBuilder
import com.intellij.ide.structureView.StructureViewModel
import com.intellij.ide.structureView.StructureViewTreeElement
import com.intellij.ide.structureView.TextEditorBasedStructureViewModel
import com.intellij.ide.structureView.TreeBasedStructureViewBuilder
import com.intellij.ide.util.treeView.smartTree.TreeElement
import com.intellij.lang.PsiStructureViewFactory
import com.intellij.navigation.ItemPresentation
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.pom.Navigatable
import com.intellij.psi.PsiFile
import com.moonsonglabs.daml.DamlIcons
import com.moonsonglabs.daml.navigation.DamlModuleNames
import javax.swing.Icon

class DamlStructureViewFactory : PsiStructureViewFactory {
    override fun getStructureViewBuilder(psiFile: PsiFile): StructureViewBuilder =
        object : TreeBasedStructureViewBuilder() {
            override fun createStructureViewModel(editor: Editor?): StructureViewModel =
                DamlStructureViewModel(psiFile, editor)
        }
}

private class DamlStructureViewModel(
    private val file: PsiFile,
    editor: Editor?
) : TextEditorBasedStructureViewModel(editor, file), StructureViewModel.ElementInfoProvider {
    override fun getRoot(): StructureViewTreeElement = DamlStructureTreeElement.root(file)

    override fun isAlwaysShowsPlus(element: StructureViewTreeElement): Boolean =
        (element as? DamlStructureTreeElement)?.childrenElements?.isNotEmpty() == true

    override fun isAlwaysLeaf(element: StructureViewTreeElement): Boolean =
        (element as? DamlStructureTreeElement)?.childrenElements?.isEmpty() != false

    override fun getSuitableClasses(): Array<Class<*>> = arrayOf(PsiFile::class.java)
}

private class DamlStructureTreeElement(
    private val file: PsiFile,
    private val item: DamlStructureItem?,
    val childrenElements: List<DamlStructureTreeElement>
) : StructureViewTreeElement {
    override fun getValue(): Any = item?.let { file.findElementAt(it.offset) ?: file } ?: file

    override fun getPresentation(): ItemPresentation =
        item?.presentation() ?: PresentationData(file.name, null, DamlIcons.File, null)

    override fun getChildren(): Array<TreeElement> = childrenElements.toTypedArray()

    override fun navigate(requestFocus: Boolean) {
        val target = item ?: return
        OpenFileDescriptor(file.project, file.virtualFile, target.offset).navigate(requestFocus)
    }

    override fun canNavigate(): Boolean = item != null && file.virtualFile != null

    override fun canNavigateToSource(): Boolean = canNavigate()

    private fun DamlStructureItem.presentation(): ItemPresentation =
        PresentationData(
            name,
            kind.location,
            kind.icon,
            null
        )

    companion object {
        fun root(file: PsiFile): DamlStructureTreeElement {
            val structure = DamlStructureParser.parse(file.text)
            val moduleChildren = buildList {
                structure.importGroup()?.let { add(element(file, it)) }
                structure.declarations.forEach { add(element(file, it)) }
            }
            val rootChildren = structure.module?.let { module ->
                listOf(DamlStructureTreeElement(file, module, moduleChildren))
            } ?: moduleChildren
            return DamlStructureTreeElement(file, null, rootChildren)
        }

        private fun element(file: PsiFile, item: DamlStructureItem): DamlStructureTreeElement =
            DamlStructureTreeElement(
                file = file,
                item = item,
                childrenElements = item.children.map { element(file, it) }
            )
    }
}

data class DamlStructure(
    val module: DamlStructureItem?,
    val imports: List<DamlStructureItem>,
    val declarations: List<DamlStructureItem>
) {
    fun importGroup(): DamlStructureItem? {
        if (imports.isEmpty()) return null
        return DamlStructureItem(
            name = "imports",
            kind = DamlStructureKind.IMPORT_GROUP,
            offset = imports.minOf { it.offset },
            children = imports
        )
    }
}

data class DamlStructureItem(
    val name: String,
    val kind: DamlStructureKind,
    val offset: Int,
    val children: List<DamlStructureItem> = emptyList()
)

enum class DamlStructureKind(
    val location: String?,
    val icon: Icon
) {
    MODULE("module", AllIcons.Nodes.Module),
    IMPORT_GROUP(null, AllIcons.Nodes.Folder),
    IMPORT("import", AllIcons.Nodes.Include),
    TEMPLATE("template", AllIcons.Nodes.Template),
    INTERFACE("interface", AllIcons.Nodes.Interface),
    DATA("data", AllIcons.Nodes.Record),
    NEWTYPE("newtype", AllIcons.Nodes.Type),
    TYPE("type", AllIcons.Nodes.Type),
    CLASS("class", AllIcons.Nodes.Class),
    EXCEPTION("exception", AllIcons.Nodes.ExceptionClass),
    CHOICE("choice", AllIcons.Nodes.Method),
    FUNCTION("function", AllIcons.Nodes.Function),
    VALUE("value", AllIcons.Nodes.Variable)
}

object DamlStructureParser {
    private val moduleRegex = Regex("""^\s*module\s+([A-Z][A-Za-z0-9_']*(?:\.[A-Z][A-Za-z0-9_']*)*)\s*(?:\([^)]*\)\s*)?where\b""")
    private val importRegex = Regex("""^\s*import\s+(?:qualified\s+)?([A-Z][A-Za-z0-9_']*(?:\.[A-Z][A-Za-z0-9_']*)*)\b(?:\s+as\s+([A-Z][A-Za-z0-9_']*))?""")
    private val typeRegex = Regex("""^\s*(interface|template|data|newtype|type|class|exception)\s+([A-Z][A-Za-z0-9_']*)\b""")
    private val choiceRegex = Regex("""^\s*(?:(?:nonconsuming|preconsuming|postconsuming)\s+)?choice\s+([A-Z][A-Za-z0-9_']*)\b""")
    private val valueRegex = Regex("""^\s*([a-z_][A-Za-z0-9_']*)\s*(?::|=(?!=))""")
    private val ignoredValueNames = setOf(
        "module", "import", "where", "let", "in", "do", "case", "of", "if", "then", "else",
        "with", "controller", "signatory", "observer", "agreement", "ensure", "key", "maintainer"
    )

    fun parse(text: String): DamlStructure {
        val declarations = mutableListOf<DamlStructureItem>()
        val imports = mutableListOf<DamlStructureItem>()
        var module: DamlStructureItem? = null
        var offset = 0

        text.lineSequence().forEach { line ->
            val indent = line.indexOfFirst { !it.isWhitespace() }.let { if (it == -1) line.length else it }
            val codeOffset = offset + indent
            if (indent < line.length && DamlModuleNames.isCodePosition(text, codeOffset)) {
                if (module == null) {
                    moduleRegex.find(line)?.let { match ->
                        val group = match.groups[1]!!
                        module = DamlStructureItem(group.value, DamlStructureKind.MODULE, offset + group.range.first)
                    }
                }

                importRegex.find(line)?.let { match ->
                    val moduleGroup = match.groups[1]!!
                    val alias = match.groups[2]?.value
                    val name = if (alias == null) moduleGroup.value else "${moduleGroup.value} as $alias"
                    imports += DamlStructureItem(name, DamlStructureKind.IMPORT, offset + moduleGroup.range.first)
                }

                declaration(line, offset, indent)?.let { declarations += it }
            }
            offset += line.length + 1
        }

        return DamlStructure(module, imports, declarations.distinctBy { it.kind to it.offset })
    }

    private fun declaration(line: String, lineOffset: Int, indent: Int): DamlStructureItem? {
        typeRegex.find(line)?.let { match ->
            val kind = when (match.groups[1]!!.value) {
                "template" -> DamlStructureKind.TEMPLATE
                "interface" -> DamlStructureKind.INTERFACE
                "data" -> DamlStructureKind.DATA
                "newtype" -> DamlStructureKind.NEWTYPE
                "type" -> DamlStructureKind.TYPE
                "class" -> DamlStructureKind.CLASS
                "exception" -> DamlStructureKind.EXCEPTION
                else -> null
            } ?: return null
            val name = match.groups[2]!!
            return DamlStructureItem(name.value, kind, lineOffset + name.range.first)
        }

        choiceRegex.find(line)?.let { match ->
            val name = match.groups[1]!!
            return DamlStructureItem(name.value, DamlStructureKind.CHOICE, lineOffset + name.range.first)
        }

        if (indent == 0) {
            valueRegex.find(line)?.let { match ->
                val name = match.groups[1]!!
                val value = name.value
                if (value !in ignoredValueNames) {
                    val kind = if (line.substring(name.range.last + 1).trimStart().startsWith(":")) {
                        DamlStructureKind.FUNCTION
                    } else {
                        DamlStructureKind.VALUE
                    }
                    return DamlStructureItem(value, kind, lineOffset + name.range.first)
                }
            }
        }

        return null
    }
}
