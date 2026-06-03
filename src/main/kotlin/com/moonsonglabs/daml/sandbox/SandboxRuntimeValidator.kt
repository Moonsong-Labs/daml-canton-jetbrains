package com.moonsonglabs.daml.sandbox

import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.process.CapturingProcessHandler
import com.moonsonglabs.daml.runtime.RuntimeEnvironment
import com.moonsonglabs.daml.settings.DamlProjectSettings
import com.moonsonglabs.daml.workspace.DamlWorkspaceService
import java.net.ServerSocket
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path

@Service(Service.Level.PROJECT)
class SandboxRuntimeValidator(private val project: Project) {
    data class Check(val name: String, val ok: Boolean, val detail: String)
    data class Result(val checks: List<Check>) {
        val ok: Boolean get() = checks.all { it.ok }
        val message: String get() = if (ok) "Managed Canton sandbox runtime is ready." else
            "Sandbox validation failed: " + checks.filterNot { it.ok }.joinToString(", ") { it.name }
    }

    fun validate(
        profile: SandboxProfile,
        generated: SandboxGeneratedFiles? = null,
        checkDarContents: Boolean = true
    ): Result {
        val runtimeProfile = SandboxProjectPaths.runtimeProfile(profile, DamlWorkspaceService.getInstance(project))
        val checks = mutableListOf<Check>()
        checks += validateTopology(runtimeProfile)
        checks += validatePorts(runtimeProfile)
        checks += validateDars(runtimeProfile, checkDarContents)
        checks += validateCanton()
        generated?.let { checks += validateGeneratedFiles(it) }
        return Result(checks)
    }

    private fun validateTopology(profile: SandboxProfile): List<Check> {
        val checks = mutableListOf<Check>()
        checks += Check("participants", profile.participants.isNotEmpty(), "${profile.participants.size} participant(s)")
        checks += Check("synchronizers", profile.synchronizers.isNotEmpty(), "${profile.synchronizers.size} synchronizer(s)")
        val identifiers = profile.participants.map { it.name } +
            profile.synchronizers.flatMap { listOf(it.name, it.sequencer.name, it.mediator.name) }
        val badIdentifiers = identifiers.filterNot { it.matches(Regex("[A-Za-z][A-Za-z0-9_]*")) }
        checks += Check("node names", badIdentifiers.isEmpty(), if (badIdentifiers.isEmpty()) "all names are Canton identifiers" else badIdentifiers.joinToString())
        val duplicates = identifiers.groupingBy { it }.eachCount().filterValues { it > 1 }.keys
        checks += Check("unique node names", duplicates.isEmpty(), if (duplicates.isEmpty()) "all node names unique" else duplicates.joinToString())
        val participantIds = profile.participants.map { it.id }.toSet()
        val synchronizerIds = profile.synchronizers.map { it.id }.toSet()
        val badBindings = profile.bindings.filter { it.participantId !in participantIds || it.synchronizerId !in synchronizerIds }
        checks += Check("bindings", badBindings.isEmpty(), if (badBindings.isEmpty()) "all bindings target existing nodes" else "${badBindings.size} invalid binding(s)")
        val disconnected = profile.participants.filter { p -> profile.bindings.none { it.participantId == p.id && it.connected } }
        checks += Check("participant connections", disconnected.isEmpty(), if (disconnected.isEmpty()) "all participants connected" else disconnected.joinToString { it.name })
        val invalidPartyAllocations = profile.partyAllocations.filterNot { allocation ->
            allocation.participantId in participantIds &&
                allocation.synchronizerId in synchronizerIds &&
                profile.bindings.any {
                    it.participantId == allocation.participantId &&
                        it.synchronizerId == allocation.synchronizerId &&
                        it.connected
                }
        }
        checks += Check(
            "party allocations",
            invalidPartyAllocations.isEmpty(),
            if (invalidPartyAllocations.isEmpty()) {
                "all parties target connected participant/synchronizer pairs"
            } else {
                invalidPartyAllocations.joinToString { "${it.partyHint}:${it.participantId}->${it.synchronizerId}" }
            }
        )
        checks += Check("cross-participant route", true, sharedSynchronizerDetail(profile))
        return checks
    }

    private fun sharedSynchronizerDetail(profile: SandboxProfile): String {
        if (profile.participants.size <= 1) return "single participant"
        val connectedByParticipant = profile.participants.associate { participant ->
            participant.id to profile.bindings
                .filter { it.participantId == participant.id && it.connected }
                .map { it.synchronizerId }
                .toSet()
        }
        val missingPairs = profile.participants.flatMapIndexed { index, left ->
            profile.participants.drop(index + 1).mapNotNull { right ->
                val shared = connectedByParticipant.getValue(left.id).intersect(connectedByParticipant.getValue(right.id))
                if (shared.isEmpty()) "${left.name}<->${right.name}" else null
            }
        }
        return if (missingPairs.isEmpty()) {
            "every participant pair has at least one shared synchronizer"
        } else {
            "independent pairs without a shared synchronizer: ${missingPairs.joinToString()}; cross-party transactions need a shared synchronizer"
        }
    }

    private fun validatePorts(profile: SandboxProfile): List<Check> {
        val ports = mutableListOf<Pair<String, Int>>()
        profile.participants.forEach {
            ports += "${it.name} ledger" to it.ledgerPort
            ports += "${it.name} admin" to it.adminPort
            ports += "${it.name} json" to it.jsonPort
        }
        profile.synchronizers.forEach {
            ports += "${it.sequencer.name} public" to it.sequencer.publicPort
            ports += "${it.sequencer.name} admin" to it.sequencer.adminPort
            ports += "${it.mediator.name} admin" to it.mediator.adminPort
        }
        val duplicate = ports.groupBy { it.second }.filterValues { it.size > 1 }
        if (duplicate.isNotEmpty()) {
            return listOf(Check("ports", false, "duplicate ports: ${duplicate.keys.joinToString()}"))
        }
        val blocked = ports.filterNot { isPortAvailable(it.second) }
        return listOf(Check("ports", blocked.isEmpty(), if (blocked.isEmpty()) "${ports.size} ports available" else "in use: ${blocked.joinToString { "${it.first}=${it.second}" }}"))
    }

    private fun validateDars(profile: SandboxProfile, checkDarContents: Boolean): List<Check> {
        val assigned = profile.darAssignments.map { it.darPath }.distinct()
        val resolved = assigned.associateWith { SandboxPaths.resolveProfilePath(it, profile, projectRoot()) }
        val missing = resolved.filterNot { Files.isRegularFile(it.value) && it.value.fileName.toString().endsWith(".dar", ignoreCase = true) }.keys
        val participantsWithDar = profile.darAssignments.flatMap { it.participantIds }.toSet()
        val partyParticipantsWithoutDar = profile.participants.filter { participant ->
            participant.id !in participantsWithDar && profile.partyAllocations.any { it.participantId == participant.id }
        }
        val existence = Check(
            "DAR files",
            missing.isEmpty(),
            when {
                missing.isNotEmpty() -> "missing or invalid: ${missing.joinToString()}"
                assigned.isEmpty() -> "warning: no DARs assigned; topology can start but app workflows will not run"
                else -> "${profile.darAssignments.size} assignment(s)"
            }
        )
        val participantCoverage = Check(
            "DAR participant coverage",
            true,
            if (partyParticipantsWithoutDar.isEmpty()) {
                "participants with parties have DAR assignments"
            } else {
                "warning: parties without participant DARs: ${partyParticipantsWithoutDar.joinToString { it.name }}"
            }
        )
        if (missing.isNotEmpty() || assigned.isEmpty()) return listOf(existence, participantCoverage)
        if (!checkDarContents) {
            return listOf(
                existence,
                participantCoverage,
                Check("DPM validate-dar", true, "skipped during Start; run Validate Profile for SDK content validation")
            )
        }
        return listOf(existence, participantCoverage, validateDarContents(resolved.values.toList()))
    }

    private fun validateDarContents(dars: List<Path>): Check {
        val settings = DamlProjectSettings.getInstance(project)
        val dpm = RuntimeEnvironment.findExecutable("dpm", settings)
            ?: return Check("DPM validate-dar", true, "DPM not found; skipped SDK content validation")
        val failures = dars.mapNotNull { dar ->
            val command = GeneralCommandLine(dpm.toString(), "validate-dar", dar.toString())
                .withCharset(StandardCharsets.UTF_8)
                .withWorkDirectory(dar.parent.toFile())
            RuntimeEnvironment.applyLocalTools(command, settings)
            val output = CapturingProcessHandler(command).runProcess(30_000)
            if (output.exitCode == 0 && !output.isTimeout) {
                null
            } else {
                val detail = listOf(output.stdout, output.stderr)
                    .joinToString("\n")
                    .trim()
                    .ifBlank { "exit code ${output.exitCode}" }
                    .takeLast(600)
                "${dar.fileName}: $detail"
            }
        }
        return Check(
            "DPM validate-dar",
            failures.isEmpty(),
            if (failures.isEmpty()) {
                "${dars.size} DAR file(s) validated with ${dpm.fileName}"
            } else {
                failures.joinToString("; ")
            }
        )
    }

    private fun validateCanton(): List<Check> {
        val settings = DamlProjectSettings.getInstance(project)
        if (settings.cantonBinaryPath.isNotBlank()) {
            val path = Path.of(settings.cantonBinaryPath)
            if (Files.isExecutable(path) || (settings.cantonBinaryPath.endsWith(".jar") && Files.isRegularFile(path))) {
                return listOf(Check("canton", true, settings.cantonBinaryPath))
            }
        }
        RuntimeEnvironment.findExecutable("canton", settings)?.let {
            return listOf(Check("canton", true, it.toString()))
        }
        CantonJarLocator.find(settings)?.let {
            return listOf(Check("canton", true, it.toString()))
        }
        return listOf(Check("canton", false, "canton executable, CANTON_JAR, SDK canton.jar, or DPM cached Canton jar not found"))
    }

    private fun validateGeneratedFiles(files: SandboxGeneratedFiles): List<Check> =
        listOf(files.localConfig, files.localBootstrap, files.localRunScript, files.uploadScript, files.allocationScript, files.statusScript)
            .map { path -> Check("generated ${path.fileName}", Files.isRegularFile(path), path.toString()) }

    private fun isPortAvailable(port: Int): Boolean {
        if (port <= 0 || port > 65535) return false
        return runCatching { ServerSocket(port).use { true } }.getOrDefault(false)
    }

    private fun projectRoot(): Path? =
        DamlWorkspaceService.getInstance(project).projectRoot()

    companion object {
        fun getInstance(project: Project): SandboxRuntimeValidator = project.service()
    }
}
