package com.bat.v2.core

import java.util.UUID

/**
 * One server, every editable field. Link parsing fills it, the edit screen edits it,
 * OutboundBuilder turns it into Xray JSON. protocol: vless | vmess | trojan | ss
 */
data class Server(
    val id: String = UUID.randomUUID().toString(),
    val name: String = "Server",
    val protocol: String = "trojan",
    val address: String = "",
    val port: Int = 443,
    /** password (trojan/ss) or uuid (vless/vmess) */
    val secret: String = "",
    /** ss cipher or vmess security */
    val method: String = "",
    val flow: String = "",
    val encryption: String = "none",
    val alterId: Int = 0,
    /** tcp | ws | grpc | httpupgrade | xhttp */
    val network: String = "tcp",
    val headerType: String = "none",
    val host: String = "",
    val path: String = "",
    val mode: String = "",
    /** none | tls | reality */
    val security: String = "none",
    val sni: String = "",
    val fp: String = "chrome",
    val alpn: String = "",
    /** pinned cert sha256 (hex) */
    val pin: String = "",
    /** verifyPeerCertByName */
    val vcn: String = "",
    /** pin the server certificate automatically on first use */
    val autoPin: Boolean = false,
    val pbk: String = "",
    val sid: String = "",
    val spx: String = "",
    /** split the TLS hello into small pieces (helps against DPI) */
    val fragment: Boolean = false,
    /** id of the subscription this server came from (empty = added by hand) */
    val sub: String = ""
) {
    fun toMap(): Map<String, Any?> = linkedMapOf(
        "id" to id, "name" to name, "protocol" to protocol, "address" to address, "port" to port,
        "secret" to secret, "method" to method, "flow" to flow, "encryption" to encryption,
        "alterId" to alterId, "network" to network, "headerType" to headerType, "host" to host,
        "path" to path, "mode" to mode, "security" to security, "sni" to sni, "fp" to fp,
        "alpn" to alpn, "pin" to pin, "vcn" to vcn, "autoPin" to autoPin, "pbk" to pbk,
        "sid" to sid, "spx" to spx, "fragment" to fragment, "sub" to sub
    )

    companion object {
        fun fromMap(m: Map<*, *>): Server {
            fun s(k: String, d: String = "") = (m[k] as? String) ?: d
            fun n(k: String, d: Int) = (m[k] as? Number)?.toInt() ?: d
            return Server(
                id = s("id", UUID.randomUUID().toString()), name = s("name", "Server"),
                protocol = s("protocol", "trojan"), address = s("address"), port = n("port", 443),
                secret = s("secret"), method = s("method"), flow = s("flow"),
                encryption = s("encryption", "none"), alterId = n("alterId", 0),
                network = s("network", "tcp"), headerType = s("headerType", "none"),
                host = s("host"), path = s("path"), mode = s("mode"), security = s("security", "none"),
                sni = s("sni"), fp = s("fp", "chrome"), alpn = s("alpn"), pin = s("pin"), vcn = s("vcn"),
                autoPin = (m["autoPin"] as? Boolean) ?: false, pbk = s("pbk"), sid = s("sid"), spx = s("spx"),
                fragment = (m["fragment"] as? Boolean) ?: false, sub = s("sub")
            )
        }
    }
}
