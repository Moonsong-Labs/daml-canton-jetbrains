package com.moonsonglabs.daml.sandbox

import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.net.ServerSocket
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.time.Duration
import java.util.UUID
import java.util.concurrent.TimeUnit

class SandboxDockerIntegrationTest {
    @Test
    fun `generated local recipe boots in Docker Canton runtime`() {
        assumeTrue(
            "Docker integration tests require -PrunDockerIntegration=true or RUN_DOCKER_INTEGRATION=true.",
            System.getProperty("runDockerIntegration") == "true" ||
                System.getenv("RUN_DOCKER_INTEGRATION") == "true"
        )
        val docker = findExecutable("docker") ?: error("docker CLI not found")
        val image = System.getProperty("cantonDockerImage") ?: "canton-jetbrains-dev:latest"
        assertCommand(docker, "image", "inspect", image)

        val root = Files.createTempDirectory("canton-sandbox-it")
        val json1 = freePort()
        val json2 = freePort()
        val profile = SandboxDefaults.newProfile(root).apply {
            id = "it-${UUID.randomUUID().toString().take(8)}"
            generatedPath = root.resolve(".canton-sandboxes/$id").toString()
            participants[0].jsonPort = json1
            participants.add(SandboxDefaults.participant(2, portBase).apply { jsonPort = json2 })
            bindings.add(ParticipantSyncBinding(participants[1].id, synchronizers[0].id, true))
            partyAllocations.add(PartyAllocation("Bob", participants[1].id, synchronizers[0].id))
        }
        val generated = SandboxGenerator().generate(profile)
        val containerName = "canton-sandbox-it-${UUID.randomUUID().toString().take(8)}"
        val outputFile = root.resolve("docker-output.log")
        val cantonCommand = """
            CANTON_JAR="${'$'}{CANTON_JAR:-/home/daml/.dpm/cache/components/canton-enterprise/3.4.11/lib/canton-enterprise-3.4.11.jar}"
            if [ ! -f "${'$'}CANTON_JAR" ]; then
              CANTON_JAR="${'$'}(find /home/daml/.dpm/cache/components -path '*/lib/canton*.jar' -type f 2>/dev/null | sort -r | head -n 1)"
            fi
            test -f "${'$'}CANTON_JAR"
            exec java -jar "${'$'}CANTON_JAR" daemon -c /work/local/canton.conf --bootstrap /work/local/bootstrap.canton --log-level-stdout WARN
        """.trimIndent()
        val process = ProcessBuilder(
            docker,
            "run",
            "--rm",
            "--name",
            containerName,
            "-v",
            "${generated.root}:/work",
            "-w",
            "/work",
            "-p",
            "$json1:$json1",
            "-p",
            "$json2:$json2",
            "--entrypoint",
            "sh",
            image,
            "-lc",
            cantonCommand
        )
            .redirectErrorStream(true)
            .redirectOutput(outputFile.toFile())
            .start()

        try {
            assertTrue("issuer JSON API did not become ready. Output:\n${readText(outputFile)}", waitForReady(json1))
            assertTrue("investor JSON API did not become ready. Output:\n${readText(outputFile)}", waitForReady(json2))
        } finally {
            ProcessBuilder(docker, "rm", "-f", containerName).redirectErrorStream(true).start().waitFor(20, TimeUnit.SECONDS)
            process.destroyForcibly()
        }
    }

    private fun waitForReady(port: Int): Boolean {
        val client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build()
        val deadline = System.currentTimeMillis() + 120_000
        while (System.currentTimeMillis() < deadline) {
            val ok = runCatching {
                listOf("livez", "readyz").all { path ->
                    val request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:$port/$path"))
                        .timeout(Duration.ofSeconds(2))
                        .GET()
                        .build()
                    client.send(request, HttpResponse.BodyHandlers.ofString()).statusCode() in 200..299
                }
            }.getOrDefault(false)
            if (ok) return true
            Thread.sleep(1_000)
        }
        return false
    }

    private fun assertCommand(vararg command: String) {
        val process = ProcessBuilder(*command).redirectErrorStream(true).start()
        val finished = process.waitFor(30, TimeUnit.SECONDS)
        val output = process.inputStream.bufferedReader().readText()
        assertTrue("Command timed out: ${command.joinToString(" ")}\n$output", finished)
        assertTrue("Command failed: ${command.joinToString(" ")}\n$output", process.exitValue() == 0)
    }

    private fun readText(path: java.nio.file.Path): String =
        runCatching { Files.readString(path).takeLast(12_000) }.getOrDefault("")

    private fun freePort(): Int = ServerSocket(0).use { it.localPort }

    private fun findExecutable(name: String): String? =
        System.getenv("PATH")
            ?.split(java.io.File.pathSeparator)
            ?.asSequence()
            ?.map { java.io.File(it, name) }
            ?.firstOrNull { it.canExecute() }
            ?.absolutePath
}
