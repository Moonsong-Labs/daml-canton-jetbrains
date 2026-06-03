package com.moonsonglabs.daml.sandbox

import com.moonsonglabs.daml.workspace.DamlWorkspaceService
import java.nio.file.Files
import java.nio.file.Path

object SandboxProjectPaths {
    fun runtimeProfile(profile: SandboxProfile, workspaceService: DamlWorkspaceService): SandboxProfile {
        val projectRoot = workspaceService.projectRoot()
        val workspace = effectiveWorkspace(profile, workspaceService) ?: return profile
        val anchored = copyProfile(profile, workspacePath = workspace.toString())
        return SandboxPaths.profileForConfig(
            anchored,
            projectRoot,
            SandboxPaths.generatedRoot(anchored, projectRoot)
        )
    }

    fun effectiveWorkspace(profile: SandboxProfile, workspaceService: DamlWorkspaceService): Path? {
        val projectRoot = workspaceService.projectRoot()
        val candidates = linkedSetOf<Path>()
        SandboxPaths.workspaceRoot(profile, projectRoot)?.let(candidates::add)
        workspaceService.defaultPackageWorkspace()?.let(candidates::add)
        workspaceService.defaultWorkspace()?.let(candidates::add)
        candidates.addAll(workspaceService.discoverWorkspaces())
        projectRoot?.let(candidates::add)

        return candidates
            .map { it.toAbsolutePath().normalize() }
            .firstOrNull { workspaceHasAssignedDars(profile, it) }
            ?: candidates.firstOrNull()?.toAbsolutePath()?.normalize()
    }

    private fun workspaceHasAssignedDars(profile: SandboxProfile, workspace: Path): Boolean {
        val assigned = profile.darAssignments.map { it.darPath }.filter { it.isNotBlank() }.distinct()
        return assigned.isNotEmpty() && assigned.all { Files.isRegularFile(resolveAgainst(workspace, it)) }
    }

    private fun resolveAgainst(base: Path, rawPath: String): Path {
        val path = Path.of(rawPath)
        return if (path.isAbsolute) path.normalize() else base.resolve(path).normalize()
    }

    private fun copyProfile(profile: SandboxProfile, workspacePath: String): SandboxProfile =
        SandboxProfile(
            id = profile.id,
            name = profile.name,
            workspacePath = workspacePath,
            cantonVersion = profile.cantonVersion,
            portBase = profile.portBase,
            participants = profile.participants.map { it.copy() }.toMutableList(),
            synchronizers = profile.synchronizers.map { it.copyNode() }.toMutableList(),
            bindings = profile.bindings.map { it.copy() }.toMutableList(),
            darAssignments = profile.darAssignments.map { DarAssignment(it.darPath, it.participantIds.toMutableList()) }.toMutableList(),
            partyAllocations = profile.partyAllocations.map { it.copy() }.toMutableList(),
            topologyPositions = profile.topologyPositions.map { it.copy() }.toMutableList(),
            generatedPath = profile.generatedPath
        )

    private fun SynchronizerNode.copyNode(): SynchronizerNode =
        SynchronizerNode(
            id = id,
            name = name,
            sequencer = sequencer.copy(),
            mediator = mediator.copy()
        )
}
