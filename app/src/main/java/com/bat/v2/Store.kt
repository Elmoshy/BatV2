package com.bat.v2

import android.content.Context
import com.bat.v2.core.Json
import com.bat.v2.core.Server
import com.bat.v2.core.Subscription

/** Saves servers and subscriptions as JSON in SharedPreferences. Safe to call from several threads. */
class Store(ctx: Context) {
    private val sp = ctx.applicationContext.getSharedPreferences("bat", Context.MODE_PRIVATE)

    @Synchronized fun load(): MutableList<Server> {
        val txt = sp.getString("servers", null) ?: return mutableListOf()
        return try {
            (Json.parse(txt) as List<*>).mapNotNull { (it as? Map<*, *>)?.let(Server::fromMap) }.toMutableList()
        } catch (e: Exception) { mutableListOf() }
    }

    @Synchronized fun save(list: List<Server>) {
        sp.edit().putString("servers", Json.write(list.map { it.toMap() })).apply()
    }

    /** read, change and write in one step */
    @Synchronized fun update(f: (MutableList<Server>) -> List<Server>) { save(f(load())) }

    @Synchronized fun upsert(s: Server) = update { l ->
        val i = l.indexOfFirst { it.id == s.id }
        if (i >= 0) l[i] = s else l.add(s)
        l
    }

    fun get(id: String): Server? = load().firstOrNull { it.id == id }

    @Synchronized fun loadSubs(): MutableList<Subscription> {
        val txt = sp.getString("subs", null) ?: return mutableListOf()
        return try {
            (Json.parse(txt) as List<*>).mapNotNull { (it as? Map<*, *>)?.let(Subscription::fromMap) }.toMutableList()
        } catch (e: Exception) { mutableListOf() }
    }

    @Synchronized fun saveSubs(list: List<Subscription>) {
        sp.edit().putString("subs", Json.write(list.map { it.toMap() })).apply()
    }

    var selectedId: String?
        get() = sp.getString("selected", null)
        set(v) { sp.edit().putString("selected", v).apply() }
}
