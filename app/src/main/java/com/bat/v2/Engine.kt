package com.bat.v2

import android.app.Activity
import android.content.Context
import androidx.appcompat.app.AppCompatActivity
import com.bat.v2.core.CertFetcher
import com.bat.v2.core.ConfigFactory
import com.bat.v2.core.Json
import com.bat.v2.core.Server
import dev.dev7.lib.v2ray.V2rayController
import dev.dev7.lib.v2ray.interfaces.LatencyDelayListener
import dev.dev7.lib.v2ray.utils.Utilities
import libv2ray.Libv2ray
import java.io.File
import java.io.RandomAccessFile
import java.net.Inet4Address
import java.net.InetAddress

/** The only file that talks to the engine module. Everything else is plain app code. */
object Engine {
    private const val DUMMY = "trojan://x@example.com:443?security=tls#x"
    private val TEST_URLS = listOf(
        "https://www.google.com/generate_204",
        "https://cp.cloudflare.com/generate_204",
        "https://www.gstatic.com/generate_204"
    )
    @Volatile private var legacyCore: Boolean? = null
    lateinit var app: Context
        private set

    class Prepared(val server: Server, val config: String, val delay: Long, val error: String?)

    fun install(c: Context) { app = c.applicationContext }

    fun init(a: AppCompatActivity) = V2rayController.init(a, R.drawable.ic_stat, a.getString(R.string.app_name))
    fun coreVersion(): String = try { V2rayController.getCoreVersion() } catch (e: Throwable) { "?" }
    fun stop(c: Context) = V2rayController.stopV2ray(c)

    // ------------------------------------------------------------------ start
    /** this app always skips the VPN (its own tests and downloads must not loop through the tunnel) plus the apps the user chose */
    private fun blockedApps(): ArrayList<String> = ArrayList<String>().apply {
        add(app.packageName)
        addAll(Prefs.bypass)
    }

    fun start(a: Activity, s: Server, cfg: String) {
        truncateLog()
        V2rayController.startV2ray(a, s.name, cfg, blockedApps())
    }

    /** restart without an activity (used by the guard); only works once the VPN permission was granted */
    @Suppress("DEPRECATION")
    fun startBackground(s: Server, cfg: String) {
        truncateLog()
        V2rayController.StartV2ray(app, s.name, cfg, blockedApps())
    }

    /** end to end delay through the RUNNING tunnel, -1 when it does not answer */
    fun connectedDelay(cb: (Long) -> Unit) {
        V2rayController.getConnectedV2rayServerDelay(app, LatencyDelayListener { d -> cb(d) })
    }

    // ------------------------------------------------------------------ core log file
    fun logFile(): File = File(app.filesDir, "xray.log")

    fun truncateLog() { try { RandomAccessFile(logFile(), "rw").use { it.setLength(0) } } catch (e: Exception) {} }

    fun trimLogIfBig() {
        try { val f = logFile(); if (f.exists() && f.length() > 3_000_000) RandomAccessFile(f, "rw").use { it.setLength(0) } } catch (e: Exception) {}
    }

    fun readLogTail(maxChars: Int = 40_000): String = try {
        val f = logFile()
        if (!f.exists()) "" else f.readText().let { if (it.length > maxChars) it.substring(it.length - maxChars) else it }
    } catch (e: Exception) { "" }

    // ------------------------------------------------------------------ config
    /** the library's own full config (inbounds, dns, routing) so we always match its version */
    private fun template(): String? = try { Utilities.normalizeV2rayFullConfig(DUMMY) } catch (e: Throwable) { null }

    /**
     * Old cores accept allowInsecure, new cores reject it and understand pinnedPeerCertSha256.
     * Ask the core itself instead of guessing from a version string.
     */
    private fun isLegacyCore(): Boolean {
        legacyCore?.let { return it }
        val probe = Server(protocol = "trojan", address = "example.com", port = 443, secret = "x",
            security = "tls", sni = "example.com", pin = "0".repeat(64))
        val accepted = try { Libv2ray.testConfig(ConfigFactory.full(probe, template(), true)); true } catch (e: Throwable) { false }
        legacyCore = accepted
        return accepted
    }

    /** IPv4 of the server so the core never needs DNS to reach it (null when it is already an IP or lookup fails). */
    private fun resolveIp(host: String): String? {
        if (host.contains(':') || host.all { it.isDigit() || it == '.' }) return null
        return try { InetAddress.getAllByName(host).firstOrNull { it is Inet4Address }?.hostAddress } catch (e: Exception) { null }
    }

    /** Full JSON for this server, validated by the core. Throws with the core's own message. Call off the main thread. */
    fun buildConfig(s: Server): String {
        val cfg = ConfigFactory.full(s, template(), isLegacyCore(), resolveIp(s.address),
            logFile().absolutePath, if (Prefs.detailedLog) "info" else "warning")
        Libv2ray.testConfig(cfg)
        return cfg
    }

    // ------------------------------------------------------------------ test
    private fun certProblem(msg: String): Boolean {
        val m = msg.lowercase()
        return m.contains("x509") || m.contains("certificate") || m.contains("pinned")
    }

    /** Real delay through the proxy in ms. Tries three different test urls before giving up. */
    @Suppress("UNCHECKED_CAST")
    private fun measure(cfg: String): Long {
        val root = (Json.parse(cfg) as Map<String, Any?>).toMutableMap()
        root.remove("routing"); root.remove("dns")
        root["routing"] = mapOf("domainStrategy" to "IPIfNonMatch")
        root["dns"] = mapOf("servers" to listOf("1.1.1.1"))
        val json = Json.write(root)
        var last: Exception = IllegalStateException("no response")
        for (u in TEST_URLS) {
            try {
                val d = Libv2ray.measureOutboundDelay(json, u)
                if (d > 0) return d
                last = IllegalStateException("no response from $u")
            } catch (e: Exception) {
                last = e
                if (certProblem(e.message ?: "")) break      // other urls will fail the same way
            }
        }
        throw last
    }

    /**
     * Paste-and-go: test the server and, if only the certificate is the problem
     * (fake SNI, renewed certificate), pin the certificate the server presents and test again. No dialogs.
     * Throws only for config errors. Call off the main thread.
     */
    fun prepare(store: Store, s0: Server): Prepared {
        var s = s0
        var cfg = buildConfig(s)
        var delay = -1L
        var err: String? = null
        try { delay = measure(cfg) } catch (e: Exception) { err = e.message ?: e.toString() }

        // The core often reports a bad certificate only as "closed pipe", so look at the certificate itself.
        if (err != null && s.autoPin && s.security == "tls" && s.vcn.isBlank()) {
            try {
                val info = CertFetcher.inspect(resolveIp(s.address) ?: s.address, s.port, s.sni.ifBlank { s.address })
                if ((certProblem(err) || info.bad) && info.sha256 != s.pin) {
                    AppLog.add("${s.name}: certificate " + (if (info.expired) "expired" else if (!info.nameMatches) "does not match the SNI" else "not trusted") + ", pinning " + info.sha256.take(12))
                    s = s.copy(pin = info.sha256)
                    store.upsert(s)
                    cfg = buildConfig(s)
                    err = null
                    try { delay = measure(cfg) } catch (e: Exception) { err = e.message ?: e.toString() }
                }
            } catch (e: Exception) {
                AppLog.add("${s.name}: cannot read the server certificate: " + (e.message ?: e.toString()))
            }
        }
        if (err != null) AppLog.add("${s.name}: test failed: $err") else AppLog.add("${s.name}: $delay ms")
        return Prepared(s, cfg, delay, err)
    }
}
