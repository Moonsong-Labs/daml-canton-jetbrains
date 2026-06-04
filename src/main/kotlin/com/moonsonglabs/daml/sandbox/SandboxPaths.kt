package com.moonsonglabs.daml.sandbox

import java.nio.file.Path

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
        val base = baseDirectory.toAbsolutePath().normalize()
        val resolvedTarget = if (target.isAbsolute) target.normalize() else base.resolve(target).normalize()
        val relative = runCatching { base.relativize(resolvedTarget) }
            .getOrElse { resolvedTarget.fileName ?: resolvedTarget }
        return invariantSeparators(relative.toString().ifBlank { "." })
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
