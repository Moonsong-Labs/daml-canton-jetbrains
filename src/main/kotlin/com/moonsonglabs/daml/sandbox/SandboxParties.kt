package com.moonsonglabs.daml.sandbox

import com.google.gson.JsonParser
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

data class SandboxParty(val id: String, val local: Boolean)

object SandboxParties {
    fun fetch(client: SandboxTransport, url: String, token: String?): List<SandboxParty> {
        val parties = linkedMapOf<String, SandboxParty>()
        val visited = mutableSetOf<String>()
        var page = ""
        do {
            check(visited.add(page)) { "Party pagination returned a repeated token." }
            val path = "/v2/parties?pageSize=1000" + if (page.isEmpty()) "" else "&pageToken=${URLEncoder.encode(page, StandardCharsets.UTF_8)}"
            val response = client.request("GET", url, path, token, null)
            check(response.status in 200..299) { "Party discovery returned HTTP ${response.status}: ${response.body.take(300)}" }
            val root = JsonParser.parseString(response.body).asJsonObject
            root.getAsJsonArray("partyDetails")?.forEach {
                val obj = it.asJsonObject
                val id = obj.get("party").asString
                parties[id] = SandboxParty(id, obj.get("isLocal")?.asBoolean ?: false)
            }
            page = root.get("nextPageToken")?.takeUnless { it.isJsonNull }?.asString.orEmpty()
        } while (page.isNotBlank())
        return parties.values.sortedBy { it.id }
    }
}
