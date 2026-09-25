package com.moonsonglabs.daml.sandbox

import com.google.gson.Gson
import com.google.gson.JsonParser
import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files

class SandboxReliabilityTest {
    @Test fun `delete and add preserves surviving identities and all endpoint ports stay unique`() {
        val profile = SandboxDefaults.newProfile(null)
        val removed = SandboxTopology.addParticipant(profile)
        val survivor = SandboxTopology.addParticipant(profile).copy()
        repeat(3) { SandboxTopology.addSynchronizer(profile) }
        profile.participants.removeIf { it.id == removed.id }
        profile.bindings.removeIf { it.participantId == removed.id }
        val added = SandboxTopology.addParticipant(profile)
        assertNotEquals(removed.id, added.id)
        assertEquals(removed.name, added.name)
        assertEquals(survivor, profile.participant(survivor.id))
        assertTrue(SandboxTopology.identityErrors(profile).isEmpty())
        val ports = EndpointBuilder.all(profile).map { it.port }
        assertEquals(ports.size, ports.toSet().size)
        val before = profile.deepCopy()
        val preview = SandboxTopology.rebased(profile, 28000)
        assertEquals(before, profile)
        assertEquals((28001..28000 + ports.size).toList(), EndpointBuilder.all(preview).map { it.port }.sorted())
    }

    @Test fun `global display name does not rewrite a valid synchronizer identity`() {
        val profile = SandboxDefaults.newProfile(null)
        profile.synchronizers.single().id = "custom-stable-id"
        assertEquals("custom-stable-id", SandboxDefaults.ensureSharedSynchronizer(profile).id)
    }

    @Test fun `generation and cleaning leave unowned files intact even with workspace as output root`() {
        val root = Files.createTempDirectory("safe-generation")
        val profile = SandboxDefaults.newProfile(root).apply { generatedPath = "." }
        val paths = listOf("data/user.txt", "logs/user.txt", "dars/user.dar", "local/log/existing.log")
        paths.forEach { val path = root.resolve(it); Files.createDirectories(path.parent); Files.writeString(path, "keep") }
        val generator = SandboxGenerator(root)
        val files = generator.generate(profile)
        generator.generate(profile)
        assertFalse(SandboxFileOwnership.clean(files.root))
        paths.forEach { assertEquals("keep", Files.readString(root.resolve(it))) }
        assertFalse(Files.readString(files.localConfig).contains("0.0.0.0"))
    }

    @Test fun `clean deletes only recorded runtime outputs and rejects symbolic links`() {
        val root = Files.createTempDirectory("owned-generation")
        val files = SandboxGenerator(root).generate(SandboxDefaults.newProfile(root))
        Files.writeString(files.logsDir.resolve("runtime.log"), "runtime")
        val user = Files.writeString(files.root.resolve("user.txt"), "keep")
        assertTrue(SandboxFileOwnership.clean(files.root))
        assertFalse(Files.exists(files.logsDir))
        assertTrue(Files.exists(user))
        val external = Files.createTempDirectory("outside-generated")
        Files.createSymbolicLink(files.logsDir, external)
        assertThrows(IllegalArgumentException::class.java) { SandboxFileOwnership.clean(files.root) }
        assertTrue(Files.isDirectory(external))
    }

    @Test fun `nested workspace exports round trip relative to profile file and migrate legacy exports`() {
        val root = Files.createTempDirectory("nested-export")
        val workspace = Files.createDirectories(root.resolve("packages/demo"))
        val dar = Files.writeString(workspace.resolve("demo.dar"), "dar")
        val profile = SandboxDefaults.newProfile(workspace).apply {
            workspacePath = "packages/demo"; generatedPath = ".canton-sandboxes/network"
            darAssignments.add(DarAssignment("demo.dar", mutableListOf(participants.first().id)))
        }
        val files = SandboxGenerator(root).generate(profile)
        val imported = Gson().fromJson(Files.readString(files.profileJson), SandboxProfile::class.java)
        assertEquals(2, imported.schemaVersion)
        assertEquals("../..", imported.workspacePath)
        assertEquals(".", imported.generatedPath)
        SandboxPaths.importPaths(imported, files.profileJson, workspace, root)
        assertEquals(workspace, SandboxPaths.workspaceRoot(imported, root))
        assertEquals(dar, SandboxPaths.resolveProfilePath(imported.darAssignments.single().darPath, imported, root))
        assertEquals(files.root, SandboxPaths.generatedRoot(imported, root))
        val legacy = profile.deepCopy().apply { schemaVersion = 0 }
        SandboxPaths.importPaths(legacy, files.profileJson, workspace, root)
        assertEquals(workspace, SandboxPaths.workspaceRoot(legacy, root))
        assertEquals(dar, SandboxPaths.resolveProfilePath(legacy.darAssignments.single().darPath, legacy, root))
    }

    @Test fun `party discovery follows pages and never replaces full identifiers with hints`() {
        val requested = mutableListOf<String>()
        val transport = SandboxTransport { _, _, path, _, _ ->
            requested += path
            val body = if (path.contains("pageToken")) """{"partyDetails":[{"party":"Bob::remote","isLocal":false}]}"""
                else """{"partyDetails":[{"party":"Bank::local","isLocal":true}],"nextPageToken":"next token"}"""
            SandboxHttpResponse(200, body, emptyMap(), 0)
        }
        val parties = SandboxParties.fetch(transport, "http://127.0.0.1", null)
        assertEquals(listOf(SandboxParty("Bank::local", true), SandboxParty("Bob::remote", false)), parties)
        assertEquals(2, requested.size)
        assertTrue(requested.last().contains("next+token") || requested.last().contains("next%20token"))
    }

    @Test fun `history beyond first page never resurrects archived contracts and refresh advances participant offset`() {
        val begins = mutableListOf<Long>()
        var failHistory = false
        val transport = SandboxTransport { _, _, path, _, body ->
            val response = when {
                path.startsWith("/v2/parties") -> """{"partyDetails":[{"party":"Bank::local","isLocal":true},{"party":"Additional::local","isLocal":true}]}"""
                path == "/v2/state/ledger-end" -> """{"offset":250}"""
                path == "/v2/state/active-contracts" -> "[]"
                else -> {
                    val request = JsonParser.parseString(body).asJsonObject
                    val begin = request.get("beginExclusive").asLong
                    begins += begin
                    assertTrue(request.getAsJsonObject("updateFormat").has("includeReassignments"))
                    if (failHistory) return@SandboxTransport SandboxHttpResponse(500, "history failed", emptyMap(), 0)
                    ((begin + 1)..minOf(begin + 200, 250)).joinToString(",", "[", "]") { offset ->
                        val event = if (offset == 250L) "ArchivedEvent" else "CreatedEvent"
                        """{"update":{"Transaction":{"value":{"offset":$offset,"synchronizerId":"global::id","events":[{"$event":{"contractId":"contract-${if (offset == 250L) 1 else offset}","templateId":"pkg:M:T","witnessParties":["Bank::local"]}}]}}}}"""
                    }
                }
            }
            SandboxHttpResponse(200, response, emptyMap(), 0)
        }
        val profile = SandboxDefaults.newProfile(null)
        val explorer = SandboxLedgerExplorer(transport)
        val first = explorer.fetch(profile, profile.participants.first(), null, "session-1")
        assertFalse(first.historyComplete)
        assertEquals(200, first.events.size)
        assertEquals(200L, first.historyThrough)
        assertEquals(2, first.parties.size)
        assertTrue(LedgerExplorerRows.from(first).none { it.kind == "Active" })
        val second = explorer.fetch(profile, profile.participants.first(), null, "session-1")
        assertTrue(second.historyComplete)
        assertEquals(250, second.events.size)
        assertEquals(listOf(0L, 200L), begins)
        failHistory = true
        val failed = explorer.fetch(profile, profile.participants.first(), null, "session-2")
        assertFalse(failed.historyComplete)
        assertEquals("[]", failed.rawActiveResponse)
        assertTrue(failed.warnings.any { it.contains("history unavailable") })
        assertEquals(0L, begins.last())
    }

    @Test fun `reassignment parsing preserves source target and in flight states`() {
        val created = """{"contractId":"cid","templateId":"pkg:M:T","witnessParties":["Bank::id"],"createArgument":{}}"""
        val assignment = """{"source":"source::id","target":"target::id","reassignmentId":"r1","createdEvent":$created}"""
        val explorer = SandboxLedgerExplorer()
        val events = explorer.parseUpdateEvents("""[{"update":{"Reassignment":{"value":{"offset":12,"events":[{"JsAssignmentEvent":$assignment}]}}}}]""")
        val unassigned = explorer.parseUpdateEvents("""[{"update":{"Reassignment":{"value":{"offset":13,"events":[{"JsUnassignedEvent":{"value":{"contractId":"cid","source":"source::id","target":"target::id","reassignmentId":"r1"}}}]}}}}]""")
        assertEquals("cid", unassigned.single().contractId)
        assertEquals("source::id", unassigned.single().sourceSynchronizerId)
        assertEquals("Assigned", events.single().kind)
        assertEquals("source::id", events.single().sourceSynchronizerId)
        assertEquals("target::id", events.single().targetSynchronizerId)
        val inFlight = explorer.parseInFlightContracts("""[{"contractEntry":{"JsIncompleteAssigned":{"assignedEvent":$assignment}}}]""")
        assertEquals("In-flight assignment", inFlight.single().kind)
    }
}
