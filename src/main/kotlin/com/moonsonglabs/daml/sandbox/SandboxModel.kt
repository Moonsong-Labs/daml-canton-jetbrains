package com.moonsonglabs.daml.sandbox

import java.nio.file.Path
import java.util.UUID

enum class SandboxSessionStatus(val presentableName: String) {
    STOPPED("Stopped"),
    GENERATING("Generating"),
    STARTING("Starting"),
    RUNNING("Running"),
    STOPPING("Stopping"),
    FAILED("Failed")
}

data class ParticipantNode(
    var id: String = "",
    var name: String = "",
    var adminPort: Int = 0,
    var ledgerPort: Int = 0,
    var jsonPort: Int = 0
)

data class SequencerNode(
    var id: String = "",
    var name: String = "",
    var publicPort: Int = 0,
    var adminPort: Int = 0
)

data class MediatorNode(
    var id: String = "",
    var name: String = "",
    var adminPort: Int = 0
)

data class SynchronizerNode(
    var id: String = "",
    var name: String = "",
    var sequencer: SequencerNode = SequencerNode(),
    var mediator: MediatorNode = MediatorNode()
)

data class ParticipantSyncBinding(
    var participantId: String = "",
    var synchronizerId: String = "",
    var connected: Boolean = true
)

data class DarAssignment(
    var darPath: String = "",
    var participantIds: MutableList<String> = mutableListOf()
)

data class PartyAllocation(
    var partyHint: String = "",
    var participantId: String = "",
    var synchronizerId: String = ""
)

data class TopologyNodePosition(
    var nodeId: String = "",
    var x: Int = 0,
    var y: Int = 0
)

data class SandboxProfile(
    var id: String = "",
    var name: String = "",
    var workspacePath: String = "",
    var cantonVersion: String = "3.4.x",
    var portBase: Int = SandboxDefaults.PORT_BASE,
    var participants: MutableList<ParticipantNode> = mutableListOf(),
    var synchronizers: MutableList<SynchronizerNode> = mutableListOf(),
    var bindings: MutableList<ParticipantSyncBinding> = mutableListOf(),
    var darAssignments: MutableList<DarAssignment> = mutableListOf(),
    var partyAllocations: MutableList<PartyAllocation> = mutableListOf(),
    var topologyPositions: MutableList<TopologyNodePosition> = mutableListOf(),
    var generatedPath: String = ""
) {
    fun participant(id: String): ParticipantNode? = participants.firstOrNull { it.id == id }
    fun synchronizer(id: String): SynchronizerNode? = synchronizers.firstOrNull { it.id == id }

    fun connectedSynchronizers(participantId: String): List<SynchronizerNode> =
        bindings
            .filter { it.participantId == participantId && it.connected }
            .mapNotNull { synchronizer(it.synchronizerId) }

    fun assignedDarFileNames(participantId: String): List<String> =
        darAssignments
            .filter { participantId in it.participantIds }
            .map { darDisplayName(it.darPath) }
            .distinct()
            .sorted()

    fun hasUploadedDarAssignments(): Boolean =
        darAssignments.any { it.participantIds.isNotEmpty() }
}

private fun darDisplayName(path: String): String =
    runCatching { Path.of(path).fileName?.toString() }
        .getOrNull()
        ?.takeIf { it.isNotBlank() }
        ?: path.ifBlank { "(unnamed DAR)" }

fun normalizeDarAssignments(profile: SandboxProfile) {
    val participantIds = profile.participants.map { it.id }.toSet()
    val merged = linkedMapOf<String, LinkedHashSet<String>>()
    profile.darAssignments.forEach { assignment ->
        val darPath = assignment.darPath.trim()
        if (darPath.isBlank()) return@forEach
        val ids = assignment.participantIds
            .filter { it in participantIds }
            .distinct()
        if (ids.isEmpty()) return@forEach
        merged.getOrPut(darPath) { linkedSetOf() }.addAll(ids)
    }
    profile.darAssignments = merged
        .map { (path, ids) -> DarAssignment(path, ids.toMutableList()) }
        .toMutableList()
}

data class Endpoint(
    val nodeId: String,
    val nodeName: String,
    val kind: String,
    val url: String,
    val port: Int
)

data class HealthSnapshot(
    val endpoint: Endpoint,
    val live: Boolean,
    val ready: Boolean,
    val message: String,
    val timestampMillis: Long = System.currentTimeMillis()
)

data class SandboxGeneratedFiles(
    val root: Path,
    val profileJson: Path,
    val localConfig: Path,
    val localBootstrap: Path,
    val localRunScript: Path,
    val uploadScript: Path,
    val allocationScript: Path,
    val statusScript: Path,
    val logsDir: Path
)

object SandboxDefaults {
    const val PORT_BASE = 5000
    const val GENERATED_DIR = ".canton-sandboxes"
    const val SHARED_SYNCHRONIZER_ID = "global"
    const val SHARED_SYNCHRONIZER_NAME = "global"

    fun newProfile(projectRoot: Path?): SandboxProfile {
        val id = "sandbox-${UUID.randomUUID().toString().take(8)}"
        val participant = participant(1, PORT_BASE)
        val synchronizer = sharedSynchronizer(PORT_BASE)
        return SandboxProfile(
            id = id,
            name = "Managed Canton Sandbox",
            workspacePath = projectRoot?.toString().orEmpty(),
            generatedPath = projectRoot?.resolve(GENERATED_DIR)?.resolve(id)?.toString().orEmpty(),
            participants = mutableListOf(participant),
            synchronizers = mutableListOf(synchronizer),
            bindings = mutableListOf(ParticipantSyncBinding(participant.id, synchronizer.id, true)),
            partyAllocations = mutableListOf(PartyAllocation("Bank", participant.id, synchronizer.id))
        )
    }

    fun participant(index: Int, portBase: Int): ParticipantNode {
        val offset = (index - 1) * 10
        val jsonBase = 7575 + (index - 1)
        return ParticipantNode(
            id = "participant$index",
            name = if (index == 1) "issuer" else if (index == 2) "investor" else "participant$index",
            ledgerPort = portBase + 11 + offset,
            adminPort = portBase + 12 + offset,
            jsonPort = jsonBase
        )
    }

    fun synchronizer(index: Int, portBase: Int): SynchronizerNode {
        val offset = (index - 1) * 20
        return SynchronizerNode(
            id = "sync$index",
            name = "sync$index",
            sequencer = SequencerNode(
                id = "sequencer$index",
                name = "sequencer$index",
                publicPort = portBase + 1 + offset,
                adminPort = portBase + 2 + offset
            ),
            mediator = MediatorNode(
                id = "mediator$index",
                name = "mediator$index",
                adminPort = portBase + 202 + offset
            )
        )
    }

    fun sharedSynchronizer(portBase: Int, portIndex: Int = 1): SynchronizerNode {
        val offset = (portIndex - 1) * 20
        return SynchronizerNode(
            id = SHARED_SYNCHRONIZER_ID,
            name = SHARED_SYNCHRONIZER_NAME,
            sequencer = SequencerNode(
                id = "globalSequencer",
                name = "globalSequencer",
                publicPort = portBase + 1 + offset,
                adminPort = portBase + 2 + offset
            ),
            mediator = MediatorNode(
                id = "globalMediator",
                name = "globalMediator",
                adminPort = portBase + 202 + offset
            )
        )
    }

    fun ensureSharedSynchronizer(profile: SandboxProfile): SynchronizerNode {
        profile.synchronizers.firstOrNull { isSharedSynchronizer(it.id, it.name) }?.let {
            it.id = SHARED_SYNCHRONIZER_ID
            it.name = SHARED_SYNCHRONIZER_NAME
            return it
        }

        val usedPorts = profile.synchronizers.flatMap {
            listOf(it.sequencer.publicPort, it.sequencer.adminPort, it.mediator.adminPort)
        }.toSet()
        val portIndex = generateSequence(1) { it + 1 }
            .first { index ->
                val candidate = sharedSynchronizer(profile.portBase, index)
                listOf(candidate.sequencer.publicPort, candidate.sequencer.adminPort, candidate.mediator.adminPort)
                    .none { it in usedPorts }
            }
        val shared = sharedSynchronizer(profile.portBase, portIndex)
        profile.synchronizers.add(0, shared)
        return shared
    }

    fun isSharedSynchronizer(id: String, name: String = id): Boolean =
        id == SHARED_SYNCHRONIZER_ID || name == SHARED_SYNCHRONIZER_NAME
}

object TopologyNodeIcons {
    const val PARTICIPANT = "◉"
    const val SYNCHRONIZER = "◇"
}
