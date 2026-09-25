package com.moonsonglabs.daml.sandbox

import com.google.gson.Gson
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.util.Comparator

/** Only a runtime directory created by us can be removed by Clean. Generation never cleans. */
object SandboxFileOwnership {
    private const val MANIFEST = ".generated-files.json"
    private const val RUNTIME = "local/log"
    private data class Manifest(val version: Int = 1, val root: String = "", val runtimePaths: List<String> = emptyList())
    private val gson = Gson()

    fun record(root: Path) {
        require(!Files.isSymbolicLink(root) && !Files.isSymbolicLink(root.resolve("local"))) { "Generated directories must not be symbolic links." }
        require(!Files.isSymbolicLink(root.resolve(MANIFEST))) { "Ownership manifest must not be a symbolic link." }
        require(!Files.isSymbolicLink(root.resolve(RUNTIME))) { "Runtime log directory must not be a symbolic link." }
        val canonical = root.toRealPath().toString()
        val previous = read(root)
        val log = root.resolve(RUNTIME)
        val ownsLog = previous?.let { it.root == canonical && RUNTIME in it.runtimePaths } == true || !Files.exists(log, NOFOLLOW_LINKS)
        if (!Files.exists(log, NOFOLLOW_LINKS)) Files.createDirectories(log)
        Files.writeString(root.resolve(MANIFEST), gson.toJson(Manifest(root = canonical, runtimePaths = if (ownsLog) listOf(RUNTIME) else emptyList())))
    }

    fun clean(root: Path): Boolean {
        if (!Files.isDirectory(root, NOFOLLOW_LINKS)) return false
        val manifest = read(root) ?: return false
        if (manifest.version != 1 || manifest.root != root.toRealPath().toString() || RUNTIME !in manifest.runtimePaths) return false
        val local = root.resolve("local")
        val target = root.resolve(RUNTIME)
        require(!Files.isSymbolicLink(local) && !Files.isSymbolicLink(target)) { "Refusing to clean a symbolic link." }
        if (Files.exists(target)) Files.walk(target).use { paths ->
            paths.sorted(Comparator.reverseOrder()).forEach(Files::delete)
        }
        return true
    }

    private fun read(root: Path): Manifest? = runCatching {
        val path = root.resolve(MANIFEST)
        require(!Files.isSymbolicLink(path))
        gson.fromJson(Files.readString(path), Manifest::class.java)
    }.getOrNull()
}
