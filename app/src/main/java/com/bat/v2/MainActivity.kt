package com.bat.v2

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.bat.v2.core.Diagnose
import com.bat.v2.core.LinkParser
import com.bat.v2.core.Server
import com.bat.v2.core.Subs
import com.bat.v2.core.Subscription
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import dev.dev7.lib.v2ray.utils.V2rayConstants
import java.net.URI
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {

    private lateinit var store: Store
    private val servers = mutableListOf<Server>()
    private val pool = Executors.newFixedThreadPool(4)
    private lateinit var adapter: ServerAdapter

    private lateinit var tvStatus: TextView
    private lateinit var tvSub: TextView
    private lateinit var tvSpeed: TextView
    private lateinit var tvError: TextView
    private lateinit var imgPower: ImageView

    private var connecting = false
    private var listSig = Int.MIN_VALUE

    private val editLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { reload() }

    private val filePicker = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            try {
                val text = contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText().take(1_000_000) }
                importText(text ?: "")
            } catch (e: Exception) { toast("cannot read file") }
        }
    }

    private val qrLauncher = registerForActivityResult(ScanContract()) { r ->
        if (r.contents != null) importText(r.contents)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        store = Store(this)
        Engine.init(this)

        tvStatus = findViewById(R.id.tvStatus)
        tvSub = findViewById(R.id.tvSub)
        tvSpeed = findViewById(R.id.tvSpeed)
        tvError = findViewById(R.id.tvError)
        imgPower = findViewById(R.id.imgPower)

        servers.addAll(store.load())
        adapter = ServerAdapter(servers, { store.selectedId }, Guard.pings,
            onSelect = { store.selectedId = it.id; adapter.notifyDataSetChanged(); render() },
            onEdit = { openEdit(it.id) },
            onDelete = { confirmDelete(it) })
        findViewById<RecyclerView>(R.id.rv).apply {
            layoutManager = LinearLayoutManager(this@MainActivity)
            adapter = this@MainActivity.adapter
        }

        findViewById<View>(R.id.btnPower).setOnClickListener { onPower() }
        findViewById<View>(R.id.btnAdd).setOnClickListener { showAddMenu() }
        findViewById<View>(R.id.btnPing).setOnClickListener { pingAll() }
        findViewById<View>(R.id.btnMore).setOnClickListener { showMore(it) }
        findViewById<View>(R.id.btnClear).setOnClickListener {
            if (servers.isNotEmpty()) {
                AlertDialog.Builder(this).setTitle("Delete all servers")
                    .setPositiveButton("Delete") { _, _ ->
                        servers.clear(); store.save(servers); Guard.pings.clear(); adapter.notifyDataSetChanged(); render()
                    }
                    .setNegativeButton("Cancel", null).show()
            }
        }

        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 7)

        // refresh subscriptions that are older than 12 hours
        val stale = store.loadSubs().filter { System.currentTimeMillis() - it.updated > 12 * 3600_000L }
        if (stale.isNotEmpty()) updateSubs(stale, silent = true)

        render()
    }

    override fun onResume() {
        super.onResume()
        Guard.onChange = { runOnUiThread { render() } }
        render()
    }

    override fun onPause() {
        Guard.onChange = null
        super.onPause()
    }

    override fun onDestroy() {
        pool.shutdownNow()
        super.onDestroy()
    }

    // ---------------------------------------------------------------- state
    private fun selected(): Server? =
        servers.firstOrNull { it.id == store.selectedId } ?: servers.firstOrNull()

    private fun render() {
        val s = selected()
        val st = Guard.state
        val white = getColor(R.color.white)
        val grey = getColor(R.color.grey)
        when {
            st == V2rayConstants.CONNECTION_STATES.CONNECTED -> {
                connecting = false
                val bad = Guard.health == -1
                tvStatus.text = if (bad) "Connected (no response)" else "Connected"
                tvStatus.setTextColor(getColor(if (bad) R.color.warn else R.color.ok))
                imgPower.setColorFilter(white)
            }
            st == V2rayConstants.CONNECTION_STATES.CONNECTING || connecting -> {
                tvStatus.text = "Connecting..."; tvStatus.setTextColor(getColor(R.color.warn)); imgPower.setColorFilter(getColor(R.color.warn))
            }
            else -> {
                tvStatus.text = "Disconnected"; tvStatus.setTextColor(grey); imgPower.setColorFilter(grey)
            }
        }
        val p = s?.let { Guard.pings[it.id] }
        tvSub.text = if (s == null) "No server" else s.name + (if (p != null && p > 0) "  ·  $p ms" else "")
        if (st == V2rayConstants.CONNECTION_STATES.CONNECTED && Guard.duration.isNotEmpty()) {
            tvSpeed.visibility = View.VISIBLE
            tvSpeed.text = "↑ ${Guard.up}   ↓ ${Guard.down}   ·   ${Guard.duration}"
        } else tvSpeed.visibility = View.GONE
        // redraw the list only when something it shows changed (this function also runs every second for the speed line)
        val sig = Guard.pings.hashCode() * 31 + (store.selectedId?.hashCode() ?: 0) + servers.size
        if (sig != listSig) { listSig = sig; adapter.notifyDataSetChanged() }
    }

    private fun showError(msg: String?) {
        tvError.visibility = if (msg.isNullOrBlank()) View.GONE else View.VISIBLE
        tvError.text = msg ?: ""
    }

    private fun reload() {
        servers.clear(); servers.addAll(store.load())
        listSig = Int.MIN_VALUE     // names or fields may have changed even when the count did not
        render()
    }

    // ---------------------------------------------------------------- power
    private fun onPower() {
        if (Guard.state != V2rayConstants.CONNECTION_STATES.DISCONNECTED) {
            Guard.userStopped = true
            Engine.stop(this); connecting = false; render(); return
        }
        val s = selected() ?: run { toast("Add a server first"); return }
        showError(null)
        Guard.userStopped = false
        connecting = true; render()
        pool.execute {
            val r = try { Engine.prepare(store, s) } catch (e: Exception) {
                runOnUiThread { if (!isDestroyed) { connecting = false; showError(e.message ?: e.toString()); render() } }
                return@execute
            }
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                Guard.pings[r.server.id] = if (r.error == null) r.delay else -1L
                if (r.server != s) reload()
                showError(r.error)
                try {
                    Guard.currentId = r.server.id
                    Engine.start(this, r.server, r.config)
                } catch (e: Exception) { connecting = false; showError(e.message ?: e.toString()) }
                render()
            }
        }
    }

    // ---------------------------------------------------------------- ping
    private fun pingAll() {
        showError(null)
        servers.toList().forEach { s ->
            Guard.pings[s.id] = -2L
            pool.execute {
                val err: String? = try {
                    val r = Engine.prepare(store, s)
                    Guard.pings[s.id] = if (r.error == null) r.delay else -1L
                    r.error
                } catch (e: Exception) { Guard.pings[s.id] = -1L; e.message ?: e.toString() }
                runOnUiThread {
                    if (isDestroyed) return@runOnUiThread
                    val i = servers.indexOfFirst { it.id == s.id }
                    if (i >= 0) adapter.notifyItemChanged(i)
                    render()
                    if (err != null && s.id == selected()?.id) showError(err)
                }
            }
        }
        adapter.notifyDataSetChanged()
    }

    // ---------------------------------------------------------------- more menu
    private fun showMore(anchor: View) {
        val items = listOf(
            "Diagnose selected server", "Core log", "Update subscriptions", "Add subscription",
            "Manage subscriptions", "Apps that bypass the VPN", "Settings", "About"
        )
        val m = PopupMenu(this, anchor)
        items.forEachIndexed { i, t -> m.menu.add(0, i, i, t) }
        m.setOnMenuItemClickListener {
            when (it.itemId) {
                0 -> diagnose()
                1 -> showLog()
                2 -> updateSubs(null, silent = false)
                3 -> addSubscription()
                4 -> manageSubs()
                5 -> pickBypassApps()
                6 -> showSettings()
                7 -> showText("About", "BatV2\ncore: ${Engine.coreVersion()}\n\nTrojan, VLESS, VMess and Shadowsocks over the xray core.")
            }
            true
        }
        m.show()
    }

    private fun diagnose() {
        val s = selected() ?: run { toast("Add a server first"); return }
        val dlg = AlertDialog.Builder(this).setTitle("Diagnosing...")
            .setMessage("DNS, TCP, TLS and the tunnel test. This takes a few seconds.").setCancelable(false).show()
        pool.execute {
            val steps = Diagnose.run(s) {
                try {
                    val r = Engine.prepare(store, s)
                    if (r.error == null) true to "${r.delay} ms through the proxy" else false to r.error
                } catch (e: Exception) { false to (e.message ?: e.toString()) }
            }
            val verdict = Diagnose.verdict(steps)
            AppLog.add("diagnose ${s.name}: $verdict")
            runOnUiThread {
                dlg.dismiss()
                if (!isDestroyed) showText("Diagnosis: ${s.name}", verdict + "\n\n" + Diagnose.format(steps))
            }
        }
    }

    private fun showLog() {
        val text = "== app ==\n" + AppLog.dump() + "\n\n== core ==\n" + Engine.readLogTail().ifBlank { "(empty)" }
        showText("Log", text) { AppLog.clear(); Engine.truncateLog() }
    }

    private fun showSettings() {
        val labels = arrayOf(
            "Restart the tunnel automatically when it stops working",
            "Switch to the best other server when restarting does not help",
            "Detailed core log (uses more battery)"
        )
        val checked = booleanArrayOf(Prefs.autoReconnect, Prefs.autoFailover, Prefs.detailedLog)
        AlertDialog.Builder(this).setTitle("Settings")
            .setMultiChoiceItems(labels, checked) { _, i, v -> checked[i] = v }
            .setPositiveButton("Save") { _, _ ->
                Prefs.autoReconnect = checked[0]; Prefs.autoFailover = checked[1]; Prefs.detailedLog = checked[2]
                toast("Saved. The log level applies at the next connect")
            }.setNegativeButton("Cancel", null).show()
    }

    private fun pickBypassApps() {
        toast("Loading apps...")
        pool.execute {
            val pm = packageManager
            val apps = pm.getInstalledApplications(0)
                .filter { it.packageName != packageName && pm.getLaunchIntentForPackage(it.packageName) != null }
                .map { it.packageName to pm.getApplicationLabel(it).toString() }
                .sortedBy { it.second.lowercase() }
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                val current = Prefs.bypass
                val checked = BooleanArray(apps.size) { apps[it].first in current }
                AlertDialog.Builder(this).setTitle("Apps that bypass the VPN")
                    .setMultiChoiceItems(apps.map { it.second }.toTypedArray(), checked) { _, i, v -> checked[i] = v }
                    .setPositiveButton("Save") { _, _ ->
                        Prefs.bypass = apps.indices.filter { checked[it] }.map { apps[it].first }.toSet()
                        toast("Saved. Applies at the next connect")
                    }.setNegativeButton("Cancel", null).show()
            }
        }
    }

    private fun showText(title: String, text: String, onClear: (() -> Unit)? = null) {
        val tv = TextView(this).apply {
            this.text = text
            typeface = Typeface.MONOSPACE
            textSize = 11f
            setTextColor(getColor(R.color.white))
            setTextIsSelectable(true)
            setPadding(40, 20, 40, 20)
        }
        val b = AlertDialog.Builder(this).setTitle(title).setView(ScrollView(this).apply { addView(tv) })
            .setPositiveButton("Close", null)
            .setNeutralButton("Copy") { _, _ ->
                val cb = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cb.setPrimaryClip(ClipData.newPlainText(title, text))
                toast("Copied")
            }
        if (onClear != null) b.setNegativeButton("Clear") { _, _ -> onClear() }
        b.show()
    }

    // ---------------------------------------------------------------- subscriptions
    private fun addSubscription() {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(48, 16, 48, 0) }
        val name = EditText(this).apply { hint = "Name (optional)"; setSingleLine(true); setTextColor(getColor(R.color.white)) }
        val url = EditText(this).apply { hint = "https://..."; setSingleLine(true); setTextColor(getColor(R.color.white)) }
        box.addView(name); box.addView(url)
        AlertDialog.Builder(this).setTitle("Add subscription").setView(box)
            .setPositiveButton("Add") { _, _ -> createSubscription(name.text.toString().trim(), url.text.toString().trim()) }
            .setNegativeButton("Cancel", null).show()
    }

    private fun createSubscription(name: String, url: String) {
        if (!url.startsWith("http://") && !url.startsWith("https://")) { toast("The link must start with http or https"); return }
        val host = try { URI(url).host ?: "subscription" } catch (e: Exception) { "subscription" }
        val sub = Subscription(name = name.ifBlank { host }, url = url)
        store.saveSubs(store.loadSubs() + sub)
        updateSubs(listOf(sub), silent = false)
    }

    private fun updateSubs(only: List<Subscription>?, silent: Boolean) {
        val subs = only ?: store.loadSubs()
        if (subs.isEmpty()) { if (!silent) toast("No subscriptions yet"); return }
        if (!silent) toast("Updating...")
        pool.execute {
            var count = 0
            var errors = 0
            for (sb in subs) {
                try {
                    val body = Net.fetch(sb.url, Guard.state == V2rayConstants.CONNECTION_STATES.CONNECTED)
                    val incoming = Subs.parse(body, sb.id)
                    if (incoming.isEmpty()) throw IllegalStateException("no servers in the answer")
                    store.update { Subs.merge(it, sb.id, incoming) }
                    val now = System.currentTimeMillis()
                    store.saveSubs(store.loadSubs().map { if (it.id == sb.id) it.copy(updated = now) else it })
                    count += incoming.size
                    AppLog.add("subscription ${sb.name}: ${incoming.size} servers")
                } catch (e: Exception) {
                    errors++
                    AppLog.add("subscription ${sb.name}: ${e.message ?: e.toString()}")
                }
            }
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                reload()
                if (!silent || errors > 0) toast("Updated $count servers" + if (errors > 0) ", $errors failed (see the log)" else "")
            }
        }
    }

    private fun manageSubs() {
        val subs = store.loadSubs()
        if (subs.isEmpty()) { toast("No subscriptions yet"); return }
        val names = subs.map { sb -> sb.name + "  (" + servers.count { it.sub == sb.id } + ")" }.toTypedArray()
        AlertDialog.Builder(this).setTitle("Subscriptions (tap to delete)")
            .setItems(names) { _, i ->
                val sb = subs[i]
                AlertDialog.Builder(this).setTitle("Delete \"${sb.name}\"").setMessage("Its servers are removed too.")
                    .setPositiveButton("Delete") { _, _ ->
                        store.saveSubs(store.loadSubs().filter { it.id != sb.id })
                        store.update { l -> l.filter { it.sub != sb.id } }
                        reload()
                    }.setNegativeButton("Cancel", null).show()
            }.setNegativeButton("Close", null).show()
    }

    // ---------------------------------------------------------------- add / edit / delete
    private fun showAddMenu() {
        val items = arrayOf("Paste from clipboard", "Type a link", "Scan QR", "Import file", "New empty server")
        AlertDialog.Builder(this).setItems(items) { _, which ->
            when (which) {
                0 -> {
                    val cb = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    val t = cb.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(this)?.toString()
                    if (t.isNullOrBlank()) toast("Clipboard is empty") else importText(t)
                }
                1 -> {
                    val et = EditText(this).apply { hint = "vless:// vmess:// trojan:// ss:// or a subscription link"; setSingleLine(false); setTextColor(getColor(R.color.white)) }
                    AlertDialog.Builder(this).setTitle("Link").setView(et)
                        .setPositiveButton("Add") { _, _ -> importText(et.text.toString()) }
                        .setNegativeButton("Cancel", null).show()
                }
                2 -> qrLauncher.launch(ScanOptions().setDesiredBarcodeFormats(ScanOptions.QR_CODE)
                    .setBeepEnabled(false).setOrientationLocked(false).setPrompt("Scan server QR"))
                3 -> filePicker.launch("*/*")
                4 -> openEdit(null)
            }
        }.show()
    }

    private fun importText(text: String) {
        val t = text.trim()
        // a single http(s) link is a subscription
        if ((t.startsWith("http://") || t.startsWith("https://")) && !t.contains('\n') && !t.contains(' ')) {
            createSubscription("", t); return
        }
        val r = LinkParser.parseAll(t)
        if (r.servers.isNotEmpty()) {
            r.servers.forEach { servers.add(it) }
            store.save(servers)
            if (store.selectedId == null) store.selectedId = r.servers.first().id
            render()
        }
        toast("Added ${r.servers.size}" + if (r.failed.isNotEmpty()) ", failed ${r.failed.size}" else "")
        if (r.failed.isNotEmpty()) showError(r.failed.joinToString("\n"))
    }

    private fun openEdit(id: String?) {
        editLauncher.launch(Intent(this, EditActivity::class.java).apply { if (id != null) putExtra("id", id) })
    }

    private fun confirmDelete(s: Server) {
        AlertDialog.Builder(this).setTitle("Delete \"${s.name}\"")
            .setPositiveButton("Delete") { _, _ ->
                servers.removeAll { it.id == s.id }; store.save(servers); Guard.pings.remove(s.id); render()
            }.setNegativeButton("Cancel", null).show()
    }

    private fun toast(m: String) = Toast.makeText(this, m, Toast.LENGTH_SHORT).show()
}
