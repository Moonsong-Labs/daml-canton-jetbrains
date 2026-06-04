package com.moonsonglabs.daml.sandbox

import org.junit.Assert.assertEquals
import org.junit.Test

class SandboxModelTest {
    @Test
    fun `normalize dar assignments removes empty duplicate and deleted participant targets`() {
        val profile = SandboxDefaults.newProfile(null)
        val participant = profile.participants.first()
        profile.darAssignments.add(DarAssignment("/tmp/app.dar", mutableListOf(participant.id, participant.id, "deleted")))
        profile.darAssignments.add(DarAssignment("/tmp/app.dar", mutableListOf(participant.id)))
        profile.darAssignments.add(DarAssignment("/tmp/empty.dar", mutableListOf("deleted")))
        profile.darAssignments.add(DarAssignment("", mutableListOf(participant.id)))

        normalizeDarAssignments(profile)

        assertEquals(1, profile.darAssignments.size)
        assertEquals("/tmp/app.dar", profile.darAssignments.single().darPath)
        assertEquals(listOf(participant.id), profile.darAssignments.single().participantIds)
    }
}
