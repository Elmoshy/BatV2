package com.bat.v2

import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.bat.v2.core.CertFetcher
import com.bat.v2.core.Server
import java.util.concurrent.Executors

/** Every field of a server in one form (like NetMod's edit screen). Save writes to the Store. */
class EditActivity : AppCompatActivity() {

    private lateinit var store: Store
    private var base = Server()
    private val texts = LinkedHashMap<String, EditText>()
    private val spinners = LinkedHashMap<String, Spinner>()
    private lateinit var swAuto: Switch
    private lateinit var swFrag: Switch
    private val pool = Executors.newSingleThreadExecutor()

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = Store(this)
        intent.getStringExtra("id")?.let { id -> store.get(id)?.let { base = it } }

        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(16), dp(16), dp(32)) }
        root.addView(title(if (intent.hasExtra("id")) "Edit server" else "New server"))

        section(root, "Server")
        text(root, "name", "Name", base.name)
        spin(root, "protocol", "Protocol", listOf("trojan", "vless", "vmess", "ss"), base.protocol)
        text(root, "address", "Address", base.address)
        text(root, "port", "Port", base.port.toString(), number = true)
        text(root, "secret", "Password / UUID", base.secret)
        spin(root, "method", "Cipher (ss) / Security (vmess)", listOf("", "auto", "aes-128-gcm", "aes-256-gcm", "chacha20-poly1305", "none"), base.method)
        spin(root, "flow", "Flow (vless)", listOf("", "xtls-rprx-vision"), base.flow)
        text(root, "encryption", "Encryption (vless)", base.encryption)
        text(root, "alterId", "AlterId (vmess)", base.alterId.toString(), number = true)

        section(root, "Transport")
        spin(root, "network", "Network", listOf("tcp", "ws", "grpc", "httpupgrade", "xhttp"), base.network)
        spin(root, "headerType", "Header type (tcp)", listOf("none", "http"), base.headerType)
        text(root, "host", "Host", base.host)
        text(root, "path", "Path / gRPC service name", base.path)
        text(root, "mode", "Mode (grpc multi / xhttp)", base.mode)

        section(root, "Security")
        spin(root, "security", "Security", listOf("none", "tls", "reality"), base.security)
        text(root, "sni", "SNI", base.sni)
        spin(root, "fp", "Fingerprint", listOf("", "chrome", "firefox", "safari", "ios", "android", "edge", "random", "randomized"), base.fp)
        text(root, "alpn", "ALPN (comma separated)", base.alpn)
        text(root, "pin", "Pinned certificate SHA-256", base.pin)
        root.addView(Button(this).apply {
            text = "Read certificate from server"
            setOnClickListener { fetchCert() }
        })
        text(root, "vcn", "Verify certificate by name (vcn)", base.vcn)
        swAuto = Switch(this).apply {
            text = "If the certificate check fails, pin the server certificate automatically"
            isChecked = base.autoPin
            setTextColor(getColor(R.color.white))
        }
        root.addView(swAuto)
        swFrag = Switch(this).apply {
            text = "Split the TLS hello (fragment) against filtering"
            isChecked = base.fragment
            setTextColor(getColor(R.color.white))
        }
        root.addView(swFrag)
        text(root, "pbk", "Reality public key", base.pbk)
        text(root, "sid", "Reality short id", base.sid)
        text(root, "spx", "Reality spider x", base.spx)

        root.addView(Button(this).apply {
            text = "Save"
            setOnClickListener { save() }
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(20) })

        setContentView(ScrollView(this).apply { addView(root); setBackgroundColor(getColor(R.color.bg)); isFillViewport = true })
    }

    override fun onDestroy() { pool.shutdownNow(); super.onDestroy() }

    // ------------------------------------------------------------ form helpers
    private fun title(t: String) = TextView(this).apply { text = t; textSize = 22f; setTextColor(getColor(R.color.white)) }

    private fun section(root: LinearLayout, t: String) = root.addView(TextView(this).apply {
        text = t.uppercase(); textSize = 12f; setTextColor(getColor(R.color.grey)); setPadding(0, dp(20), 0, dp(4))
    })

    private fun label(root: LinearLayout, t: String) = root.addView(TextView(this).apply {
        text = t; textSize = 12f; setTextColor(getColor(R.color.grey)); setPadding(0, dp(8), 0, 0)
    })

    private fun text(root: LinearLayout, key: String, label: String, value: String, number: Boolean = false) {
        label(root, label)
        val et = EditText(this).apply {
            setText(value); setSingleLine(true); setTextColor(getColor(R.color.white))
            if (number) inputType = InputType.TYPE_CLASS_NUMBER
            gravity = Gravity.START
        }
        texts[key] = et
        root.addView(et)
    }

    private fun spin(root: LinearLayout, key: String, label: String, options: List<String>, value: String) {
        label(root, label)
        val opts = if (value in options) options else options + value
        val sp = Spinner(this)
        sp.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, opts.map { if (it.isEmpty()) "(none)" else it })
        sp.setSelection(opts.indexOf(value).coerceAtLeast(0))
        spinners[key] = sp
        sp.tag = opts
        root.addView(sp)
    }

    private fun t(k: String) = texts[k]!!.text.toString().trim()

    @Suppress("UNCHECKED_CAST")
    private fun sv(k: String): String {
        val sp = spinners[k]!!
        return (sp.tag as List<String>)[sp.selectedItemPosition]
    }

    private fun collect() = base.copy(
        name = t("name").ifBlank { t("address") },
        protocol = sv("protocol"), address = t("address"),
        port = t("port").toIntOrNull() ?: 443, secret = t("secret"),
        method = sv("method"), flow = sv("flow"), encryption = t("encryption").ifBlank { "none" },
        alterId = t("alterId").toIntOrNull() ?: 0,
        network = sv("network"), headerType = sv("headerType"),
        host = t("host"), path = t("path"), mode = t("mode"),
        security = sv("security"), sni = t("sni"), fp = sv("fp"), alpn = t("alpn"),
        pin = t("pin"), vcn = t("vcn"), autoPin = swAuto.isChecked, fragment = swFrag.isChecked,
        pbk = t("pbk"), sid = t("sid"), spx = t("spx")
    )

    private fun save() {
        val s = collect()
        if (s.address.isBlank()) { Toast.makeText(this, "Address is empty", Toast.LENGTH_SHORT).show(); return }
        store.upsert(s)
        setResult(RESULT_OK)
        finish()
    }

    private fun fetchCert() {
        val s = collect()
        if (s.address.isBlank()) { Toast.makeText(this, "Address is empty", Toast.LENGTH_SHORT).show(); return }
        Toast.makeText(this, "Reading certificate...", Toast.LENGTH_SHORT).show()
        pool.execute {
            try {
                val h = CertFetcher.sha256(s.address, s.port, s.sni)
                runOnUiThread { texts["pin"]!!.setText(h) }
            } catch (e: Exception) {
                runOnUiThread { Toast.makeText(this, "Failed: " + (e.message ?: e.toString()), Toast.LENGTH_LONG).show() }
            }
        }
    }
}
