package com.bat.v2.core

/** Builds the full Xray JSON: keep the library's own template (inbounds/dns/routing), swap in our outbound. */
object ConfigFactory {

    private const val FALLBACK_TEMPLATE = """{
 "log":{"loglevel":"warning"},
 "dns":{"hosts":{"domain:googleapis.cn":"googleapis.com"},"servers":["1.1.1.1"]},
 "inbounds":[
  {"listen":"127.0.0.1","port":10808,"protocol":"socks","settings":{"auth":"noauth","udp":true,"userLevel":8},"sniffing":{"destOverride":[],"enabled":false},"tag":"socks"},
  {"listen":"127.0.0.1","port":10809,"protocol":"http","settings":{"userLevel":8},"tag":"http"}
 ],
 "outbounds":[
  {"protocol":"freedom","settings":{},"tag":"direct"},
  {"protocol":"blackhole","settings":{"response":{"type":"http"}},"tag":"block"}
 ],
 "routing":{"domainStrategy":"IPIfNonMatch","rules":[{"ip":["1.1.1.1"],"outboundTag":"proxy","port":"53","type":"field"}]}
}"""

    /** @param template full JSON the library would use; null/invalid -> built-in fallback */
    @Suppress("UNCHECKED_CAST")
    fun full(
        s: Server, template: String?, legacyCore: Boolean, ip: String? = null,
        logPath: String? = null, logLevel: String = "warning"
    ): String {
        val root = (try { Json.parse(template ?: "") as? Map<String, Any?> } catch (e: Exception) { null }
            ?: Json.parse(FALLBACK_TEMPLATE) as Map<String, Any?>).toMutableMap()
        val old = (root["outbounds"] as? List<Any?>) ?: emptyList()
        val rest = old.filter {
            val tag = (it as? Map<*, *>)?.get("tag")
            tag != "proxy" && tag != "fragment"
        }
        val head = ArrayList<Any?>()
        head.add(OutboundBuilder.build(s, legacyCore, ip))
        if (s.fragment) head.add(OutboundBuilder.fragmentOutbound())
        root["outbounds"] = head + rest
        if (logPath != null) {
            // the core writes its own log here so the app can show it
            root["log"] = linkedMapOf("loglevel" to logLevel, "error" to logPath, "access" to "none")
        }
        return Json.write(root)
    }
}
