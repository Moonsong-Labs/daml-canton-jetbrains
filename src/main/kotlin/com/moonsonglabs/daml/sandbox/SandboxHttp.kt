package com.moonsonglabs.daml.sandbox

import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.net.http.WebSocket
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage
import java.util.concurrent.TimeUnit
import com.google.gson.JsonArray
import com.google.gson.JsonParser
import java.time.Duration

data class SandboxHttpResponse(
    val status: Int,
    val body: String,
    val headers: Map<String, List<String>>,
    val durationMillis: Long = 0
)

fun interface SandboxTransport {
    fun request(method: String, baseUrl: String, path: String, token: String?, body: String?): SandboxHttpResponse
}

interface SandboxStreamingTransport : SandboxTransport {
    fun activeContracts(baseUrl: String, token: String?, body: String): SandboxHttpResponse
}

class JsonApiClient : SandboxStreamingTransport {
    private val client = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(3))
        .build()

    /** A complete finite ACS stream avoids the HTTP list cap. Never return an unfinished snapshot. */
    override fun activeContracts(baseUrl: String, token: String?, body: String): SandboxHttpResponse {
        val completion = CompletableFuture<String>()
        val rows = JsonArray()
        val frame = StringBuilder()
        var bytes = 0
        val listener = object : WebSocket.Listener {
            override fun onOpen(socket: WebSocket) { socket.request(1); socket.sendText(body, true) }
            override fun onText(socket: WebSocket, text: CharSequence, last: Boolean): CompletionStage<*>? {
                try {
                    bytes += text.length
                    check(bytes <= 50_000_000) { "ACS exceeds the inspector's 50 MB limit. Select fewer parties." }
                    frame.append(text)
                    if (last) {
                        val value = JsonParser.parseString(frame.toString()); frame.setLength(0)
                        if (value.isJsonObject && value.asJsonObject.has("code")) error(value.toString())
                        if (value.isJsonArray) value.asJsonArray.forEach(rows::add) else rows.add(value)
                    }
                    socket.request(1)
                } catch (error: Exception) { completion.completeExceptionally(error); socket.abort() }
                return null
            }
            override fun onClose(socket: WebSocket, statusCode: Int, reason: String): CompletionStage<*>? {
                if (statusCode == WebSocket.NORMAL_CLOSURE && frame.isEmpty()) completion.complete(rows.toString())
                else completion.completeExceptionally(IllegalStateException("ACS stream closed ($statusCode): $reason"))
                return null
            }
            override fun onError(socket: WebSocket, error: Throwable) { completion.completeExceptionally(error) }
        }
        val builder = client.newWebSocketBuilder().connectTimeout(Duration.ofSeconds(5))
        if (!token.isNullOrBlank()) builder.subprotocols("daml.ws.auth", "jwt.token.$token")
        else builder.subprotocols("daml.ws.auth")
        val uri = URI.create(baseUrl.replaceFirst("http", "ws").trimEnd('/') + "/v2/state/active-contracts")
        val started = System.nanoTime()
        val socket = builder.buildAsync(uri, listener).get(10, TimeUnit.SECONDS)
        return try {
            SandboxHttpResponse(200, completion.get(30, TimeUnit.SECONDS), emptyMap(), (System.nanoTime() - started) / 1_000_000)
        } finally { socket.abort() }
    }

    override fun request(
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
