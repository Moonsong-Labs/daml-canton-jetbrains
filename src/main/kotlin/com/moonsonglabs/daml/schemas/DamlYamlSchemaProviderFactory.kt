package com.moonsonglabs.daml.schemas

import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.jetbrains.jsonSchema.extension.JsonSchemaFileProvider
import com.jetbrains.jsonSchema.extension.JsonSchemaProviderFactory
import com.jetbrains.jsonSchema.extension.SchemaType

class DamlYamlSchemaProviderFactory : JsonSchemaProviderFactory {
    override fun getProviders(project: Project): List<JsonSchemaFileProvider> = listOf(
        SimpleResourceJsonSchemaProvider(
            name = "daml.yaml",
            resourcePath = "/schemas/daml-yaml-schema.json",
            fileName = "daml.yaml"),
        SimpleResourceJsonSchemaProvider(
            name = "multi-package.yaml",
            resourcePath = "/schemas/multi-package-yaml-schema.json",
            fileName = "multi-package.yaml")
    )
}

private class SimpleResourceJsonSchemaProvider(
    private val name: String,
    private val resourcePath: String,
    private val fileName: String
) : JsonSchemaFileProvider {

    override fun isAvailable(file: VirtualFile): Boolean = file.name == fileName
    override fun getName(): String = name
    override fun getSchemaFile(): VirtualFile? {
        val url = javaClass.getResource(resourcePath) ?: return null
        return com.intellij.openapi.vfs.VfsUtil.findFileByURL(url)
    }
    override fun getSchemaType(): SchemaType = SchemaType.embeddedSchema
}
