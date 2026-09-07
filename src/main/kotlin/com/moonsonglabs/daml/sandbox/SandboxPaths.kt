package com.moonsonglabs.daml.sandbox

import java.nio.file.Path
import java.nio.file.Files

object SandboxPaths {
    fun workspaceRoot(profile: SandboxProfile, projectRoot: Path? = null): Path? {
        val base = projectRoot?.toAbsolutePath()?.normalize()
        val raw = profile.workspacePath.trim().takeIf { it.isNotBlank() } ?: return base
        val path = Path.of(raw)
        return when {
            path.isAbsolute -> path.normalize()
            base != null -> base.resolve(path).normalize()
            else -> path.toAbsolutePath().normalize()
        }
    }

    fun generatedRoot(profile: SandboxProfile, projectRoot: Path? = null): Path {
        val workspace = workspaceRoot(profile, projectRoot)
        val raw = profile.generatedPath.trim().takeIf { it.isNotBlank() }
        if (raw == null) {
            return (workspace ?: Path.of("").toAbsolutePath())
                .resolve(SandboxDefaults.GENERATED_DIR)
                .resolve(profile.id)
                .normalize()
        }

        val path = Path.of(raw)
        return when {
            path.isAbsolute -> path.normalize()
            workspace != null -> workspace.resolve(path).normalize()
            projectRoot != null -> projectRoot.toAbsolutePath().normalize().resolve(path).normalize()
            else -> path.toAbsolutePath().normalize()
        }
    }

    fun resolveProfilePath(rawPath: String, profile: SandboxProfile, projectRoot: Path? = null): Path {
        val path = Path.of(rawPath)
        if (path.isAbsolute) return path.normalize()
        val workspace = workspaceRoot(profile, projectRoot) ?: Path.of("").toAbsolutePath().normalize()
        return workspace.resolve(path).normalize()
    }

    fun relativePath(baseDirectory: Path, target: Path): String {
        val lexicalBase = baseDirectory.toAbsolutePath().normalize()
        val base = canonicalLocation(lexicalBase)
        val resolvedTarget = canonicalLocation(if (target.isAbsolute) target.normalize() else lexicalBase.resolve(target).normalize())
        val relative = runCatching { base.relativize(resolvedTarget) }
            .getOrElse { resolvedTarget }
        return invariantSeparators(relative.toString().ifBlank { "." })
    }

    // macOS /var and /tmp aliases must be resolved before counting parent segments for runtime scripts.
    private fun canonicalLocation(path: Path): Path {
        var ancestor = path
        while (!Files.exists(ancestor) && ancestor.parent != null) ancestor = ancestor.parent
        return runCatching { ancestor.toRealPath().resolve(ancestor.relativize(path)).normalize() }.getOrDefault(path)
    }

    fun relativeProfilePath(rawPath: String, profile: SandboxProfile, projectRoot: Path? = null): String {
        if (rawPath.isBlank()) return ""
        val workspace = workspaceRoot(profile, projectRoot) ?: return invariantSeparators(Path.of(rawPath).normalize().toString())
        return relativePath(workspace, resolveProfilePath(rawPath, profile, projectRoot))
    }

    fun profileForConfig(profile: SandboxProfile, projectRoot: Path? = null, generatedRoot: Path? = null): SandboxProfile {
        val workspace = workspaceRoot(profile, projectRoot)
        val root = generatedRoot ?: SandboxPaths.generatedRoot(profile, projectRoot)
        val workspacePath = when {
            workspace == null -> ""
            projectRoot != null -> relativePath(projectRoot, workspace)
            else -> "."
        }
        val generatedPath = workspace?.let { relativePath(it, root) }
            ?: invariantSeparators(profile.generatedPath.ifBlank { Path.of(SandboxDefaults.GENERATED_DIR, profile.id).toString() })

        return SandboxProfile(
            id = profile.id,
            name = profile.name,
            workspacePath = workspacePath,
            cantonVersion = profile.cantonVersion,
            portBase = profile.portBase,
            participants = profile.participants.map { it.copy() }.toMutableList(),
            synchronizers = profile.synchronizers.map { it.copyNode() }.toMutableList(),
            bindings = profile.bindings.map { it.copy() }.toMutableList(),
            darAssignments = profile.darAssignments.map {
                DarAssignment(relativeProfilePath(it.darPath, profile, projectRoot), it.participantIds.toMutableList())
            }.toMutableList(),
            partyAllocations = profile.partyAllocations.map { it.copy() }.toMutableList(),
            topologyPositions = profile.topologyPositions.map { it.copy() }.toMutableList(),
            generatedPath = generatedPath
        )
    }

    /** Version 2 exports anchor every path to the JSON file, independent of IDE project roots. */
    fun profileForExport(profile: SandboxProfile, projectRoot: Path?, file: Path): SandboxProfile = profile.deepCopy().apply {
        val base = file.toAbsolutePath().parent
        workspacePath = relativePath(base, workspaceRoot(profile, projectRoot) ?: base)
        generatedPath = relativePath(base, generatedRoot(profile, projectRoot))
        darAssignments.forEach { it.darPath = relativePath(base, resolveProfilePath(it.darPath, profile, projectRoot)) }
        schemaVersion = 2
    }

    fun importPaths(profile: SandboxProfile, file: Path, inferredWorkspace: Path?, projectRoot: Path?) {
        val base = file.toAbsolutePath().parent
        if (profile.schemaVersion >= 2) {
            require(profile.schemaVersion == 2) { "Unsupported sandbox profile version ${profile.schemaVersion}" }
            profile.workspacePath = base.resolve(profile.workspacePath.ifBlank { "." }).normalize().toString()
            profile.generatedPath = base.resolve(profile.generatedPath.ifBlank { "." }).normalize().toString()
            profile.darAssignments.forEach { it.darPath = base.resolve(it.darPath).normalize().toString() }
        } else if (inferredWorkspace != null) {
            val raw = Path.of(profile.workspacePath.ifBlank { "." })
            val legacyProjectPath = projectRoot?.resolve(raw)?.normalize()
            val isGenerated = file.fileName.toString() == "profile.json" && base.parent?.fileName?.toString() == SandboxDefaults.GENERATED_DIR
            profile.workspacePath = if (isGenerated && (legacyProjectPath == inferredWorkspace.normalize() ||
                    (!raw.isAbsolute && raw.toString() != "." && inferredWorkspace.endsWith(raw) &&
                        !java.nio.file.Files.exists(inferredWorkspace.resolve(raw))))) {
                inferredWorkspace.toString()
            } else inferredWorkspace.resolve(raw).normalize().toString()
        }
    }

    fun invariantSeparators(value: String): String =
        value.replace('\\', '/')

    private fun SynchronizerNode.copyNode(): SynchronizerNode =
        SynchronizerNode(
            id = id,
            name = name,
            sequencer = sequencer.copy(),
            mediator = mediator.copy()
        )
}
