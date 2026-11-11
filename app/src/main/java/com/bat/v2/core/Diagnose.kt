package com.bat.v2.core

import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket

/** Step by step connection check: where exactly does it break (DNS, TCP, TLS or the proxy itself). */
object Diagnose {

    class Step(val name: String, val ok: Boolean?, val detail: String, val certBad: Boolean = false)

    /** @param tunnel runs the real proxy test, returns (worked, text) */
    fun run(s: Server, tunnel: () -> Pair<Boolean, String>): List<Step> {
        val steps = ArrayList<Step>()
        val isIp = s.address.contains(':') || s.address.all { it.isDigit() || it == '.' }
        var ip = s.address

        if (isIp) {
            steps.add(Step("DNS", null, "not needed, the address is an IP"))
        } else {
            val t0 = System.currentTimeMillis()
            try {
                val all = InetAddress.getAllByName(s.address)
                ip = (all.firstOrNull { it is Inet4Address } ?: all[0]).hostAddress ?: s.address
                steps.add(Step("DNS", true, all.joinToString(", ") { it.hostAddress ?: "?" } + "  (${System.currentTimeMillis() - t0} ms)"))
            } catch (e: Exception) {
                steps.add(Step("DNS", false, e.message ?: e.toString()))
                steps.add(Step("TCP", null, "skipped"))
                steps.add(Step("TLS", null, "skipped"))
                steps.add(Step("Tunnel", null, "skipped"))
                return steps
            }
        }

        val t1 = System.currentTimeMillis()
        try {
            Socket().use { it.connect(InetSocketAddress(ip, s.port), 5000) }
            steps.add(Step("TCP", true, "$ip:${s.port} accepted the connection  (${System.currentTimeMillis() - t1} ms)"))
        } catch (e: Exception) {
            steps.add(Step("TCP", false, "$ip:${s.port} " + (e.message ?: e.toString())))
            steps.add(Step("TLS", null, "skipped"))
            steps.add(Step("Tunnel", null, "skipped"))
            return steps
        }

        if (s.security == "tls") {
            val t2 = System.currentTimeMillis()
            val name = s.sni.ifBlank { s.address }
            try {
                val i = CertFetcher.inspect(ip, s.port, name)
                val sb = StringBuilder()
                sb.append("handshake ok  (${System.currentTimeMillis() - t2} ms)  ${i.protocol}\n")
                sb.append("certificate: ${i.subject}\n")
                sb.append("issuer: ${i.issuer}\n")
                sb.append("expires: ${i.notAfter}${if (i.expired) "   << EXPIRED" else ""}\n")
                sb.append("valid for \"$name\": ${if (i.nameMatches) "yes" else "NO"}\n")
                sb.append("trusted by the phone: ${if (i.trusted) "yes" else "NO"}\n")
                sb.append("sha256: ${i.sha256}")
                steps.add(Step("TLS", true, sb.toString(), i.bad))
            } catch (e: Exception) {
                steps.add(Step("TLS", false, "handshake failed: " + (e.message ?: e.toString())))
            }
        } else {
            steps.add(Step("TLS", null, "security is ${s.security}, not checked"))
        }

        val r = try { tunnel() } catch (e: Exception) { false to (e.message ?: e.toString()) }
        steps.add(Step("Tunnel", r.first, r.second))
        return steps
    }

    fun format(steps: List<Step>): String = steps.joinToString("\n\n") {
        val mark = when (it.ok) { true -> "[OK]  "; false -> "[FAIL]"; null -> "[--]  " }
        "$mark ${it.name}\n${it.detail}"
    }

    fun verdict(steps: List<Step>): String {
        fun st(n: String) = steps.firstOrNull { it.name == n }
        return when {
            st("TLS")?.certBad == true && st("Tunnel")?.ok == false ->
                "The server certificate is expired or does not match the SNI so the core refuses it. Keep the auto pin switch on in the edit screen and press Power again: the app pins the certificate by itself."
            st("DNS")?.ok == false -> "The server name can not be resolved on this network."
            st("TCP")?.ok == false -> "The server port is not reachable: blocked by the network, wrong port or the server is down."
            st("TLS")?.ok == false && st("Tunnel")?.ok != true ->
                "TCP works but the TLS handshake fails: the network is probably filtering or throttling this connection. Try the TLS fragment option."
            st("Tunnel")?.ok == false ->
                "The server answers and TLS is fine but the proxy test fails: check the password or UUID and the transport settings or the server is overloaded."
            st("Tunnel")?.ok == true -> "Everything works."
            else -> "No clear result."
        }
    }
}
