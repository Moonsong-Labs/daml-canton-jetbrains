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
import com.intellij.psi.PsiFile
import com.moonsonglabs.daml.DamlIcons
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

    override fun getSuitableClasses(): Array<Class<*>> = arrayOf(PsiFile::class.java, com.moonsonglabs.daml.lang.DamlNamedElement::class.java)
}

private class DamlStructureTreeElement(
    private val file: PsiFile,
    private val item: DamlStructureItem?,
    val childrenElements: List<DamlStructureTreeElement>
) : StructureViewTreeElement {
    override fun getValue(): Any = item?.let { com.moonsonglabs.daml.lang.DamlNamedElement.at(file, it.offset) ?: file.findElementAt(it.offset) ?: file } ?: file

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
            signature ?: kind.location,
            kind.icon,
            null
        )

    companion object {
        fun root(file: PsiFile): DamlStructureTreeElement {
            val structure = DamlStructureParser.fromModel(com.moonsonglabs.daml.lang.DamlSourceModel.get(file))
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
    val children: List<DamlStructureItem> = emptyList(),
    val signature: String? = null
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
    FIELD("field", AllIcons.Nodes.Field),
    CONSTRUCTOR("constructor", AllIcons.Nodes.Class),
    METHOD("method", AllIcons.Nodes.Method),
    VALUE("value", AllIcons.Nodes.Variable)
}

object DamlStructureParser {
    fun parse(text: String): DamlStructure = fromModel(com.moonsonglabs.daml.lang.DamlSourceModel.parse(text))
    fun fromModel(model: com.moonsonglabs.daml.lang.DamlSourceModel): DamlStructure {
        val visible = model.symbols.filter { it.kind !in com.moonsonglabs.daml.lang.DamlSourceModel.PRIVATE_KINDS }
        val byOwner = visible.groupBy { it.owner }
        fun item(symbol: com.moonsonglabs.daml.lang.DamlSourceModel.Symbol): DamlStructureItem {
            val kind = runCatching { DamlStructureKind.valueOf(symbol.kind.name) }.getOrDefault(DamlStructureKind.FUNCTION)
            return DamlStructureItem(symbol.name, kind, symbol.start, byOwner[symbol.start].orEmpty().map(::item), symbol.signature)
        }
        return DamlStructure(
            model.module?.let { DamlStructureItem(it, DamlStructureKind.MODULE, model.moduleStart ?: 0) },
            model.imports.map { DamlStructureItem(it.module + (it.alias?.let { alias -> " as $alias" } ?: ""), DamlStructureKind.IMPORT, it.moduleStart) },
            visible.filter { it.owner == null }.map(::item)
        )
    }
}
