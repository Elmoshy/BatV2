package com.bat.v2.core

import java.util.UUID

data class Subscription(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val url: String,
    val updated: Long = 0L
) {
    fun toMap(): Map<String, Any?> = linkedMapOf("id" to id, "name" to name, "url" to url, "updated" to updated)

    companion object {
        fun fromMap(m: Map<*, *>) = Subscription(
            id = (m["id"] as? String) ?: UUID.randomUUID().toString(),
            name = (m["name"] as? String) ?: "",
            url = (m["url"] as? String) ?: "",
            updated = (m["updated"] as? Number)?.toLong() ?: 0L
        )
    }
}

object Subs {
    /** subscription bodies are either a plain list of links or the same list in base64 */
    fun decode(body: String): String {
        val t = body.trim()
        if (t.contains("://")) return t
        return try { String(LinkParser.b64(t), Charsets.UTF_8) } catch (e: Exception) { t }
    }

    fun parse(body: String, subId: String): List<Server> =
        LinkParser.parseAll(decode(body)).servers.map { it.copy(sub = subId) }

    private fun key(s: Server) = "${s.protocol}|${s.address}|${s.port}|${s.secret}"

    /** replaces the servers of one subscription, keeping ids and pins of servers that did not change */
    fun merge(existing: List<Server>, subId: String, incoming: List<Server>): List<Server> {
        val old = existing.filter { it.sub == subId }.groupBy { key(it) }
        val usedIds = HashSet<String>()
        val updated = incoming.map { n ->
            val o = old[key(n)]?.firstOrNull { it.id !in usedIds }
            if (o != null) {
                usedIds.add(o.id)
                n.copy(id = o.id, pin = if (n.pin.isBlank()) o.pin else n.pin, fragment = o.fragment, sub = subId)
            } else n.copy(sub = subId)
        }
        return existing.filter { it.sub != subId } + updated
    }
}
