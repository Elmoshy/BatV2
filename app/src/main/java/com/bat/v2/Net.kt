package com.bat.v2

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.URL

object Net {
    /** downloads a small text (subscription). When the tunnel is up it tries the local socks port first. */
    fun fetch(url: String, tryLocalProxy: Boolean): String {
        if (!url.startsWith("http://") && !url.startsWith("https://")) throw IOException("not an http link")
        if (tryLocalProxy) {
            try { return get(url, Proxy(Proxy.Type.SOCKS, InetSocketAddress("127.0.0.1", 10808))) } catch (e: Exception) { /* fall back to direct */ }
        }
        return get(url, null)
    }

    private fun get(url: String, proxy: Proxy?): String {
        val u = URL(url)
        val c = (if (proxy != null) u.openConnection(proxy) else u.openConnection()) as HttpURLConnection
        c.connectTimeout = 12000
        c.readTimeout = 15000
        c.setRequestProperty("User-Agent", "BatV2/1.0")
        try {
            if (c.responseCode !in 200..299) throw IOException("HTTP " + c.responseCode)
            c.inputStream.use { ins ->
                val out = ByteArrayOutputStream()
                val buf = ByteArray(8192)
                var total = 0
                while (true) {
                    val n = ins.read(buf)
                    if (n < 0) break
                    total += n
                    if (total > 2_000_000) throw IOException("response too large")
                    out.write(buf, 0, n)
                }
                return String(out.toByteArray(), Charsets.UTF_8)
            }
        } finally { c.disconnect() }
    }
}
