package com.moonsonglabs.daml.sandbox

import com.google.gson.JsonElement
import com.google.gson.JsonParser
import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.process.CapturingProcessHandler
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.moonsonglabs.daml.runtime.RuntimeEnvironment
import com.moonsonglabs.daml.settings.DamlProjectSettings
import com.moonsonglabs.daml.workspace.DamlWorkspaceService
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.Locale
import kotlin.io.path.extension
import kotlin.io.path.name

data class DarMetadata(
    val packageName: String? = null,
    val packageVersion: String? = null,
    val templateCount: Int? = null,
    val moduleCount: Int? = null
)

data class DiscoveredDar(
    val path: Path,
    val displayName: String,
    val relativePath: String,
    val sizeBytes: Long,
    val modifiedMillis: Long,
    val exists: Boolean,
    val readable: Boolean,
    val duplicateName: Boolean,
    val metadata: DarMetadata? = null
) {
    val assignable: Boolean get() = exists && readable && path.extension.equals("dar", ignoreCase = true)

    fun matches(query: String): Boolean {
        val normalized = query.trim().lowercase(Locale.ROOT)
        if (normalized.isBlank()) return true
        return listOfNotNull(
            displayName,
            relativePath,
            metadata?.packageName,
            metadata?.packageVersion
        ).any { normalized in it.lowercase(Locale.ROOT) }
    }
}

@Service(Service.Level.PROJECT)
class DarDiscoveryService(private val project: Project) {
    private val inspector = DarMetadataInspector(project)

    fun discover(profile: SandboxProfile, extraRoots: Collection<Path> = emptyList(), extraFiles: Collection<Path> = emptyList()): List<DiscoveredDar> {
        val projectRoot = DamlWorkspaceService.getInstance(project).projectRoot()
        val workspace = SandboxPaths.workspaceRoot(profile, projectRoot) ?: projectRoot
        val files = linkedSetOf<Path>()
        val roots = linkedSetOf<Path>()
        if (workspace != null) roots.addAll(defaultDarRoots(workspace))
        roots.addAll(extraRoots)

        roots.filter { Files.isDirectory(it) }.forEach { root ->
            Files.walk(root, 10).use { paths ->
                paths
                    .filter { Files.isRegularFile(it) }
                    .filter { it.extension.equals("dar", ignoreCase = true) }
                    .filter { !isExcluded(it) }
                    .forEach { files.add(it.toAbsolutePath().normalize()) }
            }
        }

        profile.darAssignments
            .mapNotNull { runCatching { SandboxPaths.resolveProfilePath(it.darPath, profile, projectRoot).toAbsolutePath().normalize() }.getOrNull() }
            .forEach(files::add)
        extraFiles.map { it.toAbsolutePath().normalize() }.forEach(files::add)

        val duplicateNames = files
            .groupingBy { it.fileName?.toString().orEmpty() }
            .eachCount()
            .filterValues { it > 1 }
            .keys

        return files
            .map { path -> toDiscoveredDar(path, workspace, path.fileName?.toString().orEmpty() in duplicateNames) }
            .sortedWith(compareBy<DiscoveredDar>({ !it.assignable }, { it.displayName.lowercase(Locale.ROOT) }, { it.path.toString() }))
    }

    private fun toDiscoveredDar(path: Path, workspace: Path?, duplicateName: Boolean): DiscoveredDar {
        val exists = Files.isRegularFile(path)
        val readable = exists && Files.isReadable(path)
        return DiscoveredDar(
            path = path,
            displayName = path.fileName?.toString() ?: path.toString(),
            relativePath = workspace?.let { runCatching { SandboxPaths.relativePath(it, path) }.getOrNull() } ?: path.toString(),
            sizeBytes = if (exists) runCatching { Files.size(path) }.getOrDefault(0L) else 0L,
            modifiedMillis = if (exists) runCatching { Files.getLastModifiedTime(path).toMillis() }.getOrDefault(0L) else 0L,
            exists = exists,
            readable = readable,
            duplicateName = duplicateName,
            metadata = if (readable) inspector.inspect(path) else null
        )
    }

    private fun defaultDarRoots(workspace: Path): List<Path> =
        listOf(
            workspace,
            workspace.resolve(".daml").resolve("dist"),
            workspace.resolve("daml").resolve(".daml").resolve("dist"),
            workspace.resolve("sdk")
        ).map { it.toAbsolutePath().normalize() }.distinct()

    private fun isExcluded(path: Path): Boolean {
        val parts = path.map { it.name }.toList()
        if (parts.windowed(2).any { it == listOf(".daml", "package-database") }) return true
        return parts.any { it in excludedNames }
    }

    companion object {
        private val excludedNames = setOf(".canton-sandboxes", ".gradle", "build", "out", "node_modules")

        fun getInstance(project: Project): DarDiscoveryService = project.service()
    }
}

class DarMetadataInspector(private val project: Project) {
    fun inspect(path: Path): DarMetadata? {
        val settings = DamlProjectSettings.getInstance(project)
        val dpm = RuntimeEnvironment.findExecutable("dpm", settings) ?: return null
        val command = GeneralCommandLine(dpm.toString(), "damlc", "inspect-dar", "--json", path.toString())
            .withCharset(StandardCharsets.UTF_8)
            .withWorkDirectory(path.parent.toFile())
        RuntimeEnvironment.applyLocalTools(command, settings)
        val output = CapturingProcessHandler(command).runProcess(15_000)
        if (output.exitCode != 0 || output.isTimeout) return null
        return parse(output.stdout)
    }

    internal fun parse(json: String): DarMetadata? =
        runCatching {
            val root = JsonParser.parseString(json)
            DarMetadata(
                packageName = firstString(root, setOf("packageName", "package_name", "name")),
                packageVersion = firstString(root, setOf("packageVersion", "package_version", "version")),
                templateCount = countMatchingKeys(root, "template").takeIf { it > 0 },
                moduleCount = countMatchingKeys(root, "module").takeIf { it > 0 }
            ).takeIf {
                it.packageName != null || it.packageVersion != null || it.templateCount != null || it.moduleCount != null
            }
        }.getOrNull()

    private fun firstString(element: JsonElement, keys: Set<String>): String? {
        if (element.isJsonObject) {
            val obj = element.asJsonObject
            for (key in keys) {
                val value = obj.get(key)
                if (value != null && value.isJsonPrimitive && value.asJsonPrimitive.isString) {
                    return value.asString.takeIf { it.isNotBlank() }
                }
            }
            obj.entrySet().forEach { entry -> firstString(entry.value, keys)?.let { return it } }
        } else if (element.isJsonArray) {
            element.asJsonArray.forEach { firstString(it, keys)?.let { value -> return value } }
        }
        return null
    }

    private fun countMatchingKeys(element: JsonElement, token: String): Int {
        if (element.isJsonObject) {
            val obj = element.asJsonObject
            return obj.entrySet().sumOf { entry ->
                val self = if (entry.key.contains(token, ignoreCase = true)) countValue(entry.value) else 0
                self + countMatchingKeys(entry.value, token)
            }
        }
        if (element.isJsonArray) return element.asJsonArray.sumOf { countMatchingKeys(it, token) }
        return 0
    }

    private fun countValue(element: JsonElement): Int =
        when {
            element.isJsonArray -> element.asJsonArray.size()
            element.isJsonObject -> element.asJsonObject.size().coerceAtLeast(1)
            else -> 1
        }
}
