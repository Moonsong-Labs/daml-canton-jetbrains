package com.moonsonglabs.daml.sandbox

import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

data class SandboxHttpResponse(
    val status: Int,
    val body: String,
    val headers: Map<String, List<String>>,
    val durationMillis: Long = 0
)

class JsonApiClient {
    private val client = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(3))
        .build()

    fun request(
        method: String,
        baseUrl: String,
        path: String,
        token: String?,
        body: String?
    ): SandboxHttpResponse {
        val uri = URI.create(baseUrl.trimEnd('/') + "/" + path.trimStart('/'))
        val builder = HttpRequest.newBuilder(uri)
            .timeout(Duration.ofSeconds(15))
            .header("accept", "application/json")
        if (!token.isNullOrBlank()) builder.header("Authorization", "Bearer $token")
        val normalizedMethod = method.uppercase()
        if (normalizedMethod == "GET" || normalizedMethod == "DELETE") {
            builder.method(normalizedMethod, HttpRequest.BodyPublishers.noBody())
        } else {
            builder.header("Content-Type", "application/json")
            builder.method(normalizedMethod, HttpRequest.BodyPublishers.ofString(body.orEmpty()))
        }
        val started = System.nanoTime()
        val response = client.send(builder.build(), HttpResponse.BodyHandlers.ofString())
        val durationMillis = (System.nanoTime() - started) / 1_000_000
        return SandboxHttpResponse(response.statusCode(), response.body(), response.headers().map(), durationMillis)
    }
}

object EndpointBuilder {
    fun participantEndpoints(profile: SandboxProfile): List<Endpoint> =
        profile.participants.flatMap { participant ->
            listOf(
                Endpoint(participant.id, participant.name, "ledger", "grpc://127.0.0.1:${participant.ledgerPort}", participant.ledgerPort),
                Endpoint(participant.id, participant.name, "admin", "grpc://127.0.0.1:${participant.adminPort}", participant.adminPort),
                Endpoint(participant.id, participant.name, "json", "http://127.0.0.1:${participant.jsonPort}", participant.jsonPort)
            )
        }

    fun synchronizerEndpoints(profile: SandboxProfile): List<Endpoint> =
        profile.synchronizers.flatMap { sync ->
            listOf(
                Endpoint(sync.sequencer.id, sync.sequencer.name, "sequencer-public", "grpc://127.0.0.1:${sync.sequencer.publicPort}", sync.sequencer.publicPort),
                Endpoint(sync.sequencer.id, sync.sequencer.name, "sequencer-admin", "grpc://127.0.0.1:${sync.sequencer.adminPort}", sync.sequencer.adminPort),
                Endpoint(sync.mediator.id, sync.mediator.name, "mediator-admin", "grpc://127.0.0.1:${sync.mediator.adminPort}", sync.mediator.adminPort)
            )
        }

    fun all(profile: SandboxProfile): List<Endpoint> = participantEndpoints(profile) + synchronizerEndpoints(profile)
}
