package com.moonsonglabs.daml.sandbox

import org.junit.Assert.assertTrue
import org.junit.Test

class SandboxParticipantInspectorTest {
    @Test
    fun `participant inspector prioritizes uploaded DARs`() {
        val profile = SandboxDefaults.newProfile(null).apply {
            darAssignments.add(DarAssignment("/workspace/.daml/dist/private-settlement.dar", mutableListOf(participants[0].id)))
        }

        val text = participantInspectorText(profile, profile.participants[0], "not checked")

        assertTrue(text.contains("Uploaded DARs:\nprivate-settlement.dar"))
        assertTrue(text.indexOf("Uploaded DARs:") < text.indexOf("Ledger API:"))
    }

    @Test
    fun `participant inspector warns when no DARs are assigned anywhere`() {
        val profile = SandboxDefaults.newProfile(null)

        val text = participantInspectorText(profile, profile.participants[0], "not checked")

        assertTrue(text.contains("! No DARs assigned to any participant."))
    }

    @Test
    fun `participant inspector warns when only the selected participant lacks DARs`() {
        val profile = SandboxDefaults.newProfile(null).apply {
            participants.add(SandboxDefaults.participant(2, portBase))
            darAssignments.add(DarAssignment("/workspace/.daml/dist/private-settlement.dar", mutableListOf(participants[0].id)))
        }

        val text = participantInspectorText(profile, profile.participants[1], "not checked")

        assertTrue(text.contains("! No DARs assigned to this participant."))
    }
}
