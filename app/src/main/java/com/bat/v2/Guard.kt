package com.bat.v2

import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import com.bat.v2.core.Server
import dev.dev7.lib.v2ray.utils.V2rayConfigs
import dev.dev7.lib.v2ray.utils.V2rayConstants
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/**
 * Lives as long as the app process: tracks the tunnel state and speed, checks that traffic really flows,
 * restarts the tunnel when it dies and moves to another server when restarting does not help.
 * Note: a restart from the background needs the VPN permission already granted (it is after the first connect).
 */
object Guard {
    private lateinit var app: Application
    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()

    /** last measured delay per server id: ms, -1 failed, -2 measuring */
    val pings = ConcurrentHashMap<String, Long>()

    @Volatile var state: V2rayConstants.CONNECTION_STATES = V2rayConstants.CONNECTION_STATES.DISCONNECTED
    @Volatile var up = ""
    @Volatile var down = ""
    @Volatile var duration = ""
    /** 0 unknown, 1 traffic flows, -1 the running tunnel does not answer */
    @Volatile var health = 0
    @Volatile var currentId: String? = null
    @Volatile var userStopped = false

    /** called on the main thread whenever something above changed */
    @Volatile var onChange: (() -> Unit)? = null

    private var failures = 0
    private var checking = false
    private var checkGen = 0
    private var recovering = false
    private var fastFail = false
    private var seenNet: Network? = null
    private val recoveries = ArrayList<Long>()

    fun install(a: Application) {
        app = a
        val filter = IntentFilter(V2rayConstants.V2RAY_SERVICE_STATICS_BROADCAST_INTENT)
        val r = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) { onBroadcast(intent) }
        }
        if (Build.VERSION.SDK_INT >= 33) a.registerReceiver(r, filter, Context.RECEIVER_EXPORTED)
        else a.registerReceiver(r, filter)

        try {
            val cm = a.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val req = NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN).build()
            cm.registerNetworkCallback(req, object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    val changed = seenNet != null && seenNet != network
                    seenNet = network
                    if (changed) main.post { onNetworkChanged() }
                }
            })
        } catch (e: Exception) { AppLog.add("network callback not available: " + e.message) }

        main.postDelayed(tick, 25_000)
    }

    // ------------------------------------------------------------------ state from the engine service
    private fun onBroadcast(intent: Intent) {
        val ex = intent.extras ?: return
        @Suppress("DEPRECATION")
        val st = ex.getSerializable(V2rayConstants.SERVICE_CONNECTION_STATE_BROADCAST_EXTRA) as? V2rayConstants.CONNECTION_STATES
        val prev = state
        if (st != null) {
            state = st
            // the engine library answers "not connected" to delay requests when its own copy of the state is stale
            V2rayConfigs.connectionState = st
        }
        up = ex.getString(V2rayConstants.SERVICE_UPLOAD_SPEED_BROADCAST_EXTRA) ?: ""
        down = ex.getString(V2rayConstants.SERVICE_DOWNLOAD_SPEED_BROADCAST_EXTRA) ?: ""
        duration = ex.getString(V2rayConstants.SERVICE_DURATION_BROADCAST_EXTRA) ?: ""
        if (state == V2rayConstants.CONNECTION_STATES.CONNECTED && prev != V2rayConstants.CONNECTION_STATES.CONNECTED) {
            failures = 0; health = 0; fastFail = false
            main.postDelayed({ check("after connect") }, 4000)
        }
        if (state == V2rayConstants.CONNECTION_STATES.DISCONNECTED) { health = 0; failures = 0 }
        onChange?.invoke()
    }

    private val tick = object : Runnable {
        override fun run() {
            try {
                if (state == V2rayConstants.CONNECTION_STATES.CONNECTED) { check("periodic"); Engine.trimLogIfBig() }
            } catch (e: Exception) { /* keep ticking */ }
            main.postDelayed(this, 25_000)
        }
    }

    private fun onNetworkChanged() {
        if (state != V2rayConstants.CONNECTION_STATES.CONNECTED) return
        AppLog.add("network changed, checking the tunnel")
        fastFail = true
        main.postDelayed({ check("network changed") }, 2500)
    }

    // ------------------------------------------------------------------ health check (main thread)
    private fun check(reason: String) {
        if (state != V2rayConstants.CONNECTION_STATES.CONNECTED || recovering || checking) return
        checking = true
        val gen = ++checkGen
        main.postDelayed({ if (checking && checkGen == gen) { checking = false; onResult(-1, reason) } }, 15_000)
        try {
            Engine.connectedDelay { d -> main.post { if (checking && checkGen == gen) { checking = false; onResult(d, reason) } } }
        } catch (e: Exception) { checking = false }
    }

    private fun onResult(d: Long, reason: String) {
        if (d > 0) {
            failures = 0; fastFail = false; health = 1
            currentId?.let { pings[it] = d }
        } else {
            failures++; health = -1
            AppLog.add("health check failed ($reason) #$failures")
            if (failures >= 2 || fastFail) recover(reason) else main.postDelayed({ check("retry") }, 5000)
        }
        onChange?.invoke()
    }

    // ------------------------------------------------------------------ recovery
    private fun recover(reason: String) {
        if (recovering || userStopped) return
        if (!Prefs.autoReconnect) { AppLog.add("tunnel looks dead, auto reconnect is off"); return }
        val now = System.currentTimeMillis()
        recoveries.removeAll { now - it > 10 * 60_000 }
        if (recoveries.size >= 4) { AppLog.add("too many recoveries in 10 minutes, waiting"); return }
        recoveries.add(now)
        recovering = true
        fastFail = false
        val store = Store(app)
        worker.execute {
            try {
                val cur = currentId?.let { store.get(it) } ?: store.selectedId?.let { store.get(it) } ?: store.load().firstOrNull()
                if (cur == null) return@execute
                AppLog.add("recovering ($reason): restarting ${cur.name}")
                toast("Reconnecting...")
                stopAndWait()
                if (userStopped) return@execute
                var done = tryStart(store, cur, true)
                if (!done && Prefs.autoFailover) {
                    for (c in candidates(store, cur)) {
                        if (userStopped) return@execute
                        AppLog.add("trying ${c.name}")
                        if (tryStart(store, c, true)) {
                            store.selectedId = c.id; currentId = c.id
                            toast("Switched to ${c.name}")
                            done = true
                            break
                        }
                    }
                }
                if (!done && !userStopped) {
                    AppLog.add("no server passed the test, starting ${cur.name} anyway")
                    tryStart(store, cur, false)
                }
            } catch (e: Exception) {
                AppLog.add("recovery failed: " + (e.message ?: e.toString()))
            } finally {
                main.post { recovering = false; failures = 0; onChange?.invoke() }
            }
        }
    }

    private fun candidates(store: Store, cur: Server): List<Server> =
        store.load().filter { it.id != cur.id }
            .sortedWith(compareBy({ if ((pings[it.id] ?: 0L) > 0) 0 else 1 }, { pings[it.id] ?: Long.MAX_VALUE }))
            .take(5)

    /** @param requireOk only start when the test passes */
    private fun tryStart(store: Store, s: Server, requireOk: Boolean): Boolean {
        val r = try { Engine.prepare(store, s) } catch (e: Exception) { AppLog.add("${s.name}: ${e.message}"); return false }
        pings[r.server.id] = if (r.error == null) r.delay else -1L
        if (requireOk && r.error != null) return false
        try { Engine.startBackground(r.server, r.config) } catch (e: Exception) {
            AppLog.add("background start was refused: " + (e.message ?: e.toString()))
            return false
        }
        return waitFor(V2rayConstants.CONNECTION_STATES.CONNECTED, 12_000)
    }

    private fun stopAndWait() {
        if (state == V2rayConstants.CONNECTION_STATES.DISCONNECTED) return
        main.post { Engine.stop(app) }
        waitFor(V2rayConstants.CONNECTION_STATES.DISCONNECTED, 6_000)
    }

    private fun waitFor(target: V2rayConstants.CONNECTION_STATES, ms: Long): Boolean {
        val end = System.currentTimeMillis() + ms
        while (System.currentTimeMillis() < end) {
            if (state == target) return true
            try { Thread.sleep(200) } catch (e: InterruptedException) { return false }
        }
        return state == target
    }

    private fun toast(m: String) = main.post { Toast.makeText(app, m, Toast.LENGTH_LONG).show() }
}
