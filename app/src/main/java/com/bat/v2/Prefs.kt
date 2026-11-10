package com.bat.v2

import android.content.Context

/** App settings. */
object Prefs {
    private fun sp() = Engine.app.getSharedPreferences("bat_prefs", Context.MODE_PRIVATE)

    /** restart the tunnel when the health check fails */
    var autoReconnect: Boolean
        get() = sp().getBoolean("autoReconnect", true)
        set(v) { sp().edit().putBoolean("autoReconnect", v).apply() }

    /** when restarting does not help, move to the best other server */
    var autoFailover: Boolean
        get() = sp().getBoolean("autoFailover", true)
        set(v) { sp().edit().putBoolean("autoFailover", v).apply() }

    /** core log level info instead of warning */
    var detailedLog: Boolean
        get() = sp().getBoolean("detailedLog", false)
        set(v) { sp().edit().putBoolean("detailedLog", v).apply() }

    /** packages that skip the VPN */
    var bypass: Set<String>
        get() = sp().getStringSet("bypass", emptySet()) ?: emptySet()
        set(v) { sp().edit().putStringSet("bypass", HashSet(v)).apply() }
}
