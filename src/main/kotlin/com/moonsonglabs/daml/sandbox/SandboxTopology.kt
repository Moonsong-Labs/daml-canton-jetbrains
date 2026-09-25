package com.moonsonglabs.daml.sandbox

import java.util.UUID

/** Allocates against every endpoint, without changing existing nodes or user overrides. */
object SandboxTopology {
    private fun allocator(profile: SandboxProfile, occupied: Set<Int> = EndpointBuilder.all(profile).map { it.port }.toSet()): () -> Int {
        require(profile.portBase in 1024..65532) { "Port base must be between 1024 and 65532." }
        val used = occupied.toMutableSet()
        var next = profile.portBase + 1
        return {
            while (next in used && next <= 65535) next++
            require(next <= 65535) { "No available ports remain above ${profile.portBase}." }
            next.also { used.add(it); next++ }
        }
    }

    fun addParticipant(profile: SandboxProfile): ParticipantNode {
        val names = nodeNames(profile).toSet()
        val index = generateSequence(1) { it + 1 }.first {
            SandboxDefaults.participant(it, profile.portBase).name !in names
        }
        val port = allocator(profile)
        val node = SandboxDefaults.participant(index, profile.portBase).apply {
            id = "participant-${UUID.randomUUID()}"
            ledgerPort = port(); adminPort = port(); jsonPort = port()
        }
        profile.participants.add(node)
        profile.synchronizers.forEach { profile.bindings.add(ParticipantSyncBinding(node.id, it.id)) }
        return node
    }

    fun addSynchronizer(profile: SandboxProfile): SynchronizerNode {
        val names = nodeNames(profile).toSet()
        val index = generateSequence(1) { it + 1 }.first {
            listOf("sync$it", "sequencer$it", "mediator$it").none(names::contains)
        }
        val node = SandboxDefaults.synchronizer(index, profile.portBase).apply {
            id = "synchronizer-${UUID.randomUUID()}"
            sequencer.id = "sequencer-${UUID.randomUUID()}"
            mediator.id = "mediator-${UUID.randomUUID()}"
        }
        allocateSynchronizer(profile, node)
        profile.synchronizers.add(node)
        profile.participants.forEach { profile.bindings.add(ParticipantSyncBinding(it.id, node.id)) }
        return node
    }

    fun allocateSynchronizer(profile: SandboxProfile, node: SynchronizerNode) {
        val port = allocator(profile)
        node.sequencer.publicPort = port(); node.sequencer.adminPort = port(); node.mediator.adminPort = port()
    }

    /** Returns a preview; callers apply it only after the user reviews the changes. */
    fun rebased(profile: SandboxProfile, base: Int): SandboxProfile = profile.deepCopy().apply {
        portBase = base
        val port = allocator(this, emptySet())
        synchronizers.forEach { it.sequencer.publicPort = port(); it.sequencer.adminPort = port(); it.mediator.adminPort = port() }
        participants.forEach { it.ledgerPort = port(); it.adminPort = port(); it.jsonPort = port() }
    }

    fun nodeNames(profile: SandboxProfile): List<String> = profile.participants.map { it.name } +
        profile.synchronizers.flatMap { listOf(it.name, it.sequencer.name, it.mediator.name) }

    fun identityErrors(profile: SandboxProfile): List<String> {
        val ids = profile.participants.map { it.id } + profile.synchronizers.flatMap { listOf(it.id, it.sequencer.id, it.mediator.id) }
        val errors = mutableListOf<String>()
        if (ids.any(String::isBlank)) errors += "Node IDs must not be empty."
        val duplicates = ids.groupingBy { it }.eachCount().filterValues { it > 1 }.keys
        if (duplicates.isNotEmpty()) errors += "Duplicate node IDs: ${duplicates.joinToString()}"
        if (profile.bindings.groupingBy { it.participantId to it.synchronizerId }.eachCount().any { it.value > 1 }) {
            errors += "Each participant/synchronizer pair must have only one connection."
        }
        return errors
    }
}

fun SandboxProfile.deepCopy(): SandboxProfile = copy(
    participants = participants.map { it.copy() }.toMutableList(),
    synchronizers = synchronizers.map { it.copy(sequencer = it.sequencer.copy(), mediator = it.mediator.copy()) }.toMutableList(),
    bindings = bindings.map { it.copy() }.toMutableList(),
    darAssignments = darAssignments.map { it.copy(participantIds = it.participantIds.toMutableList()) }.toMutableList(),
    partyAllocations = partyAllocations.map { it.copy() }.toMutableList(),
    topologyPositions = topologyPositions.map { it.copy() }.toMutableList()
)

fun SandboxProfile.runtimeDefinition(): SandboxProfile = deepCopy().apply {
    name = ""; topologyPositions.clear(); schemaVersion = 0
}
