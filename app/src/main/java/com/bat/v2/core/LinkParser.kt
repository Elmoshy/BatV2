package com.bat.v2.core

/** Parses vless:// vmess:// trojan:// ss:// share links into Server objects. */
object LinkParser {

    class Result(val servers: List<Server>, val failed: List<String>)

    /** Accepts many links separated by newlines/spaces. Never throws. */
    fun parseAll(text: String): Result {
        val ok = ArrayList<Server>()
        val bad = ArrayList<String>()
        for (tok in text.split('\n', '\r', ' ', '\t')) {
            val t = tok.trim()
            if (t.isEmpty() || !t.contains("://")) continue
            try { ok.add(parseOne(t)) } catch (e: Exception) { bad.add(t.take(40) + " : " + (e.message ?: "error")) }
        }
        return Result(ok, bad)
    }

    fun parseOne(raw: String): Server {
        val line = raw.trim()
        return when (val scheme = line.substringBefore("://").lowercase()) {
            "vless", "trojan" -> uriStyle(scheme, line)
            "vmess" -> vmess(line)
            "ss" -> ss(line)
            else -> throw IllegalArgumentException("unsupported scheme '$scheme'")
        }
    }

    // ---- vless / trojan -------------------------------------------------
    private fun uriStyle(scheme: String, line: String): Server {
        val rest = line.substringAfter("://")
        val name = if (rest.contains('#')) pct(rest.substringAfter('#')) else ""
        val noFrag = rest.substringBefore('#')
        val query = if (noFrag.contains('?')) noFrag.substringAfter('?') else ""
        val main = noFrag.substringBefore('?').trimEnd('/')
        if (!main.contains('@')) throw IllegalArgumentException("missing user info")
        val secret = pct(main.substringBeforeLast('@'))
        val (addr, port) = hostPort(main.substringAfterLast('@'))
        val q = query(query)

        var net = (q["type"] ?: "tcp").lowercase()
        if (net == "raw") net = "tcp"
        val security = (q["security"] ?: if (scheme == "trojan") "tls" else "none").lowercase()
        val insecure = q["allowinsecure"] == "1" || q["allowinsecure"] == "true" || q["insecure"] == "1"
        val path = if (net == "grpc") (q["servicename"] ?: q["path"] ?: "") else (q["path"] ?: "")
        return Server(
            name = name.ifBlank { addr },
            protocol = scheme, address = addr, port = port, secret = secret,
            flow = q["flow"] ?: "", encryption = q["encryption"] ?: "none",
            network = net, headerType = q["headertype"] ?: "none",
            host = q["host"] ?: "", path = path, mode = q["mode"] ?: "",
            security = security, sni = q["sni"] ?: q["peer"] ?: "",
            fp = q["fp"] ?: "chrome", alpn = q["alpn"] ?: "",
            pin = q["pcs"] ?: "", vcn = q["vcn"] ?: "",
            autoPin = security == "tls" && (insecure || (q["pcs"].isNullOrBlank() && q["vcn"].isNullOrBlank())),
            pbk = q["pbk"] ?: "", sid = q["sid"] ?: "", spx = q["spx"] ?: ""
        )
    }

    // ---- vmess ----------------------------------------------------------
    private fun vmess(line: String): Server {
        val body = line.substringAfter("://").substringBefore('#')
        @Suppress("UNCHECKED_CAST")
        val j = Json.parse(String(b64(body), Charsets.UTF_8)) as? Map<String, Any?>
            ?: throw IllegalArgumentException("bad vmess json")
        fun s(k: String) = j[k]?.toString() ?: ""
        val addr = s("add")
        val tls = s("tls").lowercase() == "tls"
        return Server(
            name = s("ps").ifBlank { addr }, protocol = "vmess", address = addr,
            port = s("port").toIntOrNull() ?: 443, secret = s("id"),
            method = s("scy").ifBlank { "auto" }, alterId = s("aid").toIntOrNull() ?: 0,
            network = s("net").ifBlank { "tcp" }.lowercase(), headerType = s("type").ifBlank { "none" },
            host = s("host"), path = s("path"),
            security = if (tls) "tls" else "none", sni = s("sni"), fp = s("fp").ifBlank { "chrome" }, alpn = s("alpn")
        )
    }

    // ---- shadowsocks ----------------------------------------------------
    private fun ss(line: String): Server {
        val rest = line.substringAfter("://")
        val name = if (rest.contains('#')) pct(rest.substringAfter('#')) else ""
        val main = rest.substringBefore('#').substringBefore('?').trimEnd('/')
        val method: String; val pass: String; val addr: String; val port: Int
        if (main.contains('@')) {
            val ui = main.substringBeforeLast('@')
            val cred = if (ui.contains(':')) pct(ui) else String(b64(ui), Charsets.UTF_8)
            method = cred.substringBefore(':'); pass = cred.substringAfter(':')
            val hp = hostPort(main.substringAfterLast('@')); addr = hp.first; port = hp.second
        } else {
            val dec = String(b64(main), Charsets.UTF_8)
            val cred = dec.substringBeforeLast('@')
            method = cred.substringBefore(':'); pass = cred.substringAfter(':')
            val hp = hostPort(dec.substringAfterLast('@')); addr = hp.first; port = hp.second
        }
        return Server(name = name.ifBlank { addr }, protocol = "ss", address = addr, port = port,
            secret = pass, method = method)
    }

    // ---- helpers --------------------------------------------------------
    internal fun hostPort(s: String): Pair<String, Int> {
        val t = s.trim()
        if (t.startsWith("[")) {
            val end = t.indexOf(']')
            if (end < 0) throw IllegalArgumentException("bad ipv6")
            val p = t.substring(end + 1).removePrefix(":")
            return t.substring(1, end) to (p.toIntOrNull() ?: 443)
        }
        if (!t.contains(':')) return t to 443
        val p = t.substringAfterLast(':').toIntOrNull() ?: throw IllegalArgumentException("bad port")
        return t.substringBeforeLast(':') to p
    }

    private fun query(q: String): Map<String, String> {
        val m = HashMap<String, String>()
        if (q.isBlank()) return m
        for (pair in q.split('&')) {
            if (pair.isEmpty()) continue
            val k = pair.substringBefore('=').lowercase()
            val v = if (pair.contains('=')) pct(pair.substringAfter('=')) else ""
            m[k] = v
        }
        return m
    }

    /** percent-decode (keeps '+' as is, handles UTF-8) */
    internal fun pct(s: String): String {
        if (!s.contains('%')) return s
        val out = java.io.ByteArrayOutputStream()
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '%' && i + 2 < s.length) {
                val h = s.substring(i + 1, i + 3).toIntOrNull(16)
                if (h != null) { out.write(h); i += 3; continue }
            }
            out.write(c.toString().toByteArray(Charsets.UTF_8)); i++
        }
        return String(out.toByteArray(), Charsets.UTF_8)
    }

    /** tolerant base64 (standard + url-safe, padding optional) */
    internal fun b64(s: String): ByteArray {
        val clean = s.filter { !it.isWhitespace() && it != '=' }.replace('-', '+').replace('_', '/')
        val tbl = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"
        val out = java.io.ByteArrayOutputStream()
        var buf = 0; var bits = 0
        for (c in clean) {
            val v = tbl.indexOf(c)
            if (v < 0) throw IllegalArgumentException("bad base64")
            buf = (buf shl 6) or v; bits += 6
            if (bits >= 8) { bits -= 8; out.write((buf shr bits) and 0xFF) }
        }
        return out.toByteArray()
    }
}
