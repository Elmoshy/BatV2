package com.bat.v2.core

/** Server -> Xray outbound (as plain maps, serialised by Json). */
object OutboundBuilder {

    /**
     * @param legacyCore true for old Xray cores that still understand allowInsecure but not pinnedPeerCertSha256.
     */
    fun build(s: Server, legacyCore: Boolean = false, ip: String? = null): Map<String, Any?> {
        val addr = ip ?: s.address
        val settings: Map<String, Any?> = when (s.protocol) {
            "vless" -> mapOf("vnext" to listOf(mapOf(
                "address" to addr, "port" to s.port,
                "users" to listOf(linkedMapOf<String, Any?>(
                    "id" to s.secret, "encryption" to s.encryption.ifBlank { "none" }, "level" to 8
                ).also { if (s.flow.isNotBlank()) it["flow"] = s.flow })
            )))
            "vmess" -> mapOf("vnext" to listOf(mapOf(
                "address" to addr, "port" to s.port,
                "users" to listOf(mapOf(
                    "id" to s.secret, "alterId" to s.alterId,
                    "security" to s.method.ifBlank { "auto" }, "level" to 8
                ))
            )))
            "trojan" -> mapOf("servers" to listOf(mapOf(
                "address" to addr, "port" to s.port, "password" to s.secret, "level" to 8
            )))
            "ss" -> mapOf("servers" to listOf(mapOf(
                "address" to addr, "port" to s.port, "method" to s.method,
                "password" to s.secret, "level" to 8
            )))
            else -> throw IllegalArgumentException("unsupported protocol '${s.protocol}'")
        }
        return linkedMapOf(
            "tag" to "proxy",
            "protocol" to if (s.protocol == "ss") "shadowsocks" else s.protocol,
            "settings" to settings,
            "streamSettings" to stream(s, legacyCore, ip != null)
        )
    }

    /** freedom outbound that chops the TLS hello; the proxy outbound dials through it (sockopt.dialerProxy) */
    fun fragmentOutbound(): Map<String, Any?> = linkedMapOf(
        "tag" to "fragment",
        "protocol" to "freedom",
        "settings" to mapOf("fragment" to mapOf("packets" to "tlshello", "length" to "100-200", "interval" to "10-20")),
        "streamSettings" to mapOf("sockopt" to mapOf("tcpNoDelay" to true))
    )

    fun normalizePin(p: String): String =
        p.split(',').map { it.filter { c -> c != ':' && !c.isWhitespace() }.lowercase() }
            .filter { it.isNotEmpty() }.joinToString(",")

    private fun stream(s: Server, legacyCore: Boolean, dialedByIp: Boolean): Map<String, Any?> {
        // when we dial an IP the name must still be sent as SNI / Host
        val fallbackName = if (dialedByIp) s.address else ""
        val net = s.network.lowercase().let { if (it == "raw") "tcp" else it }
        val m = linkedMapOf<String, Any?>("network" to net)
        when (net) {
            "ws" -> m["wsSettings"] = linkedMapOf<String, Any?>("path" to s.path.ifBlank { "/" }).also {
                (s.host.ifBlank { fallbackName }).let { h -> if (h.isNotBlank()) it["headers"] = mapOf("Host" to h) }
            }
            "grpc" -> m["grpcSettings"] = mapOf("serviceName" to s.path, "multiMode" to (s.mode == "multi"))
            "httpupgrade" -> m["httpupgradeSettings"] = linkedMapOf<String, Any?>("path" to s.path.ifBlank { "/" }).also {
                (s.host.ifBlank { fallbackName }).let { h -> if (h.isNotBlank()) it["host"] = h }
            }
            "xhttp" -> m["xhttpSettings"] = linkedMapOf<String, Any?>("path" to s.path.ifBlank { "/" }).also {
                (s.host.ifBlank { fallbackName }).let { h -> if (h.isNotBlank()) it["host"] = h }
                if (s.mode.isNotBlank()) it["mode"] = s.mode
            }
            else -> if (s.headerType == "http") m["tcpSettings"] = mapOf("header" to mapOf(
                "type" to "http",
                "request" to mapOf(
                    "path" to listOf(s.path.ifBlank { "/" }),
                    "headers" to mapOf("Host" to s.host.split(',').map { it.trim() }.filter { it.isNotEmpty() })
                )
            ))
        }
        if (s.fragment) m["sockopt"] = mapOf("dialerProxy" to "fragment")
        when (s.security.lowercase()) {
            "tls" -> {
                m["security"] = "tls"
                val t = linkedMapOf<String, Any?>()
                (s.sni.ifBlank { fallbackName }).let { n -> if (n.isNotBlank()) t["serverName"] = n }
                if (s.fp.isNotBlank()) t["fingerprint"] = s.fp
                if (s.alpn.isNotBlank()) t["alpn"] = s.alpn.split(',').map { it.trim() }.filter { it.isNotEmpty() }
                if (legacyCore) {
                    // old cores cannot pin: a stored pin means "trust this server", so skip chain checks
                    if (s.pin.isNotBlank()) t["allowInsecure"] = true
                } else {
                    if (s.pin.isNotBlank()) t["pinnedPeerCertSha256"] = normalizePin(s.pin)
                    if (s.vcn.isNotBlank()) t["verifyPeerCertByName"] = s.vcn
                }
                m["tlsSettings"] = t
            }
            "reality" -> {
                m["security"] = "reality"
                m["realitySettings"] = linkedMapOf(
                    "serverName" to s.sni, "fingerprint" to s.fp.ifBlank { "chrome" },
                    "publicKey" to s.pbk, "shortId" to s.sid, "spiderX" to s.spx.ifBlank { "/" }
                )
            }
            else -> m["security"] = "none"
        }
        return m
    }
}
