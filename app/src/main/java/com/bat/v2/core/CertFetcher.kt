package com.bat.v2.core

import java.net.InetSocketAddress
import java.security.KeyStore
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.cert.X509Certificate
import javax.net.ssl.SNIHostName
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

/**
 * Reads the certificate a server presents for a given SNI.
 * The handshake accepts any certificate on purpose (we only READ it) and no application data is sent.
 */
object CertFetcher {

    class Info(
        val sha256: String, val subject: String, val issuer: String, val notAfter: String,
        val protocol: String, val cipher: String, val trusted: Boolean, val nameMatches: Boolean
    )

    fun sha256(host: String, port: Int, sni: String, timeoutMs: Int = 6000): String =
        inspect(host, port, sni, timeoutMs).sha256

    fun inspect(host: String, port: Int, sni: String, timeoutMs: Int = 6000): Info {
        val trustAll = object : X509TrustManager {
            override fun checkClientTrusted(c: Array<X509Certificate>, a: String) {}
            override fun checkServerTrusted(c: Array<X509Certificate>, a: String) {}
            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
        }
        val ctx = SSLContext.getInstance("TLS").apply { init(null, arrayOf(trustAll), SecureRandom()) }
        val sock = ctx.socketFactory.createSocket() as SSLSocket
        try {
            sock.soTimeout = timeoutMs
            sock.connect(InetSocketAddress(host, port), timeoutMs)
            val name = sni.ifBlank { host }
            val params = sock.sslParameters
            try { params.serverNames = listOf(SNIHostName(name)) } catch (e: Exception) { /* IP literal: no SNI */ }
            sock.sslParameters = params
            sock.startHandshake()
            val session = sock.session
            val chain = session.peerCertificates.map { it as X509Certificate }.toTypedArray()
            val leaf = chain[0]
            val sha = MessageDigest.getInstance("SHA-256").digest(leaf.encoded).joinToString("") { "%02x".format(it) }
            return Info(
                sha, leaf.subjectX500Principal.name, leaf.issuerX500Principal.name, leaf.notAfter.toString(),
                session.protocol, session.cipherSuite, systemTrusts(chain), nameMatches(leaf, name)
            )
        } finally {
            try { sock.close() } catch (e: Exception) {}
        }
    }

    internal fun systemTrusts(chain: Array<X509Certificate>): Boolean = try {
        val tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
        tmf.init(null as KeyStore?)
        val tm = tmf.trustManagers.filterIsInstance<X509TrustManager>().first()
        tm.checkServerTrusted(chain, if (chain[0].publicKey.algorithm == "RSA") "RSA" else "ECDHE_ECDSA")
        true
    } catch (e: Exception) { false }

    /** does the certificate cover this host name (SAN first, CN only when there is no SAN) */
    internal fun nameMatches(cert: X509Certificate, host: String): Boolean {
        val names = ArrayList<String>()
        try {
            cert.subjectAlternativeNames?.forEach { e ->
                val t = e[0] as? Int
                if (t == 2 || t == 7) names.add(e[1].toString())
            }
        } catch (e: Exception) {}
        if (names.isEmpty()) {
            Regex("CN=([^,]+)").find(cert.subjectX500Principal.name)?.let { names.add(it.groupValues[1]) }
        }
        return names.any { matchHost(it, host) }
    }

    internal fun matchHost(pattern: String, host: String): Boolean {
        val p = pattern.lowercase(); val h = host.lowercase()
        if (p == h) return true
        if (p.startsWith("*.")) {
            val suffix = p.substring(1)                       // ".example.com"
            return h.endsWith(suffix) && h.length > suffix.length && !h.substring(0, h.length - suffix.length).contains('.')
        }
        return false
    }
}
