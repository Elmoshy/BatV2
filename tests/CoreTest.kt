import com.bat.v2.core.*

var failures = 0
fun check(name: String, ok: Boolean) { println((if (ok) "PASS " else "FAIL ") + name); if (!ok) failures++ }

@Suppress("UNCHECKED_CAST")
fun main() {
    val t = LinkParser.parseOne("trojan://pass123@de.connfull.org:2087/?security=tls&fp=chrome&sni=www.speedtest.net&type=tcp&headerType=none#Go")
    check("trojan host", t.address == "de.connfull.org" && t.port == 2087)
    check("trojan secret/name", t.secret == "pass123" && t.name == "Go")
    check("trojan sni/fp/security", t.sni == "www.speedtest.net" && t.fp == "chrome" && t.security == "tls")
    check("tls link gets auto-pin fallback by default", t.autoPin)
    check("explicit pcs disables auto pin", !LinkParser.parseOne("trojan://p@h.com:443?security=tls&pcs=ab#x").autoPin)
    check("plain link has no auto pin", !LinkParser.parseOne("vless://u@h.com:443?security=none#x").autoPin)

    val pinned = t.copy(pin = "AA:BB:cc:" + "00".repeat(29))
    val newCfg = Json.parse(ConfigFactory.full(pinned, null, false)) as Map<String, Any?>
    val out0 = ((newCfg["outbounds"] as List<Any?>)[0]) as Map<String, Any?>
    val tls = ((out0["streamSettings"] as Map<String, Any?>)["tlsSettings"]) as Map<String, Any?>
    check("new core: serverName kept", tls["serverName"] == "www.speedtest.net")
    check("new core: pin normalised", tls["pinnedPeerCertSha256"] == "aabbcc" + "00".repeat(29))
    check("new core: no allowInsecure", !tls.containsKey("allowInsecure"))
    check("outbound is tagged proxy and first", out0["tag"] == "proxy")
    check("template inbounds kept", (newCfg["inbounds"] as List<*>).size == 2)

    val legacy = Json.parse(ConfigFactory.full(pinned, null, true)) as Map<String, Any?>
    val ltls = ((((legacy["outbounds"] as List<Any?>)[0] as Map<String, Any?>)["streamSettings"] as Map<String, Any?>)["tlsSettings"]) as Map<String, Any?>
    check("legacy core: allowInsecure", ltls["allowInsecure"] == true && !ltls.containsKey("pinnedPeerCertSha256"))

    val noPinLegacy = Json.parse(ConfigFactory.full(t, null, true)) as Map<String, Any?>
    val nptls = ((((noPinLegacy["outbounds"] as List<Any?>)[0] as Map<String, Any?>)["streamSettings"] as Map<String, Any?>)["tlsSettings"]) as Map<String, Any?>
    check("legacy core: no allowInsecure without a stored pin", !nptls.containsKey("allowInsecure"))

    val viaIp = Json.parse(ConfigFactory.full(t.copy(sni = ""), null, false, "57.129.113.16")) as Map<String, Any?>
    val ipOut = (viaIp["outbounds"] as List<Any?>)[0] as Map<String, Any?>
    val ipServer = (((ipOut["settings"] as Map<String, Any?>)["servers"]) as List<Map<String, Any?>>)[0]
    val ipTls = ((ipOut["streamSettings"] as Map<String, Any?>)["tlsSettings"]) as Map<String, Any?>
    check("ip override: dials the ip", ipServer["address"] == "57.129.113.16")
    check("ip override: SNI falls back to the domain", ipTls["serverName"] == "de.connfull.org")

    val tpl = """{"inbounds":[{"protocol":"socks","port":1}],"outbounds":[{"tag":"proxy","protocol":"vless"},{"tag":"direct","protocol":"freedom"}]}"""
    val merged = Json.parse(ConfigFactory.full(t, tpl, false)) as Map<String, Any?>
    val obs = merged["outbounds"] as List<Map<String, Any?>>
    check("template merge", obs.size == 2 && obs[0]["protocol"] == "trojan" && obs[1]["tag"] == "direct")

    val v = LinkParser.parseOne("vless://11111111-2222-3333-4444-555555555555@1.2.3.4:443?encryption=none&flow=xtls-rprx-vision&security=reality&sni=www.microsoft.com&fp=chrome&pbk=PUB&sid=ab12&type=tcp#My%20Reality")
    check("vless reality", v.protocol == "vless" && v.security == "reality" && v.pbk == "PUB" && v.sid == "ab12" && v.flow == "xtls-rprx-vision" && v.name == "My Reality")
    val vw = LinkParser.parseOne("vless://uuid@host.com:443?security=tls&type=ws&host=h.com&path=%2Fws%3Fed%3D2048&sni=h.com#ws")
    check("vless ws path decoded", vw.path == "/ws?ed=2048" && vw.network == "ws" && vw.host == "h.com")
    val v6 = LinkParser.parseOne("vless://uuid@[2001:db8::1]:8443?security=none#v6")
    check("ipv6 host", v6.address == "2001:db8::1" && v6.port == 8443)

    val vmJson = """{"v":"2","ps":"vm","add":"vm.example.com","port":"443","id":"uuid-1","aid":"0","scy":"auto","net":"ws","type":"none","host":"vm.example.com","path":"/p","tls":"tls","sni":"vm.example.com"}"""
    val b = java.util.Base64.getEncoder().encodeToString(vmJson.toByteArray())
    val vm = LinkParser.parseOne("vmess://$b")
    check("vmess", vm.protocol == "vmess" && vm.address == "vm.example.com" && vm.port == 443 && vm.network == "ws" && vm.security == "tls" && vm.name == "vm")

    val cred = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString("aes-256-gcm:pw".toByteArray())
    val s1 = LinkParser.parseOne("ss://$cred@5.6.7.8:8388#ss1")
    check("ss sip002", s1.method == "aes-256-gcm" && s1.secret == "pw" && s1.address == "5.6.7.8" && s1.port == 8388)
    val whole = java.util.Base64.getEncoder().encodeToString("aes-128-gcm:pw2@9.9.9.9:1234".toByteArray())
    val s2 = LinkParser.parseOne("ss://$whole#ss2")
    check("ss legacy", s2.method == "aes-128-gcm" && s2.secret == "pw2" && s2.address == "9.9.9.9" && s2.port == 1234)

    val ai = LinkParser.parseOne("trojan://p@h.com:443?security=tls&allowInsecure=1#x")
    check("allowInsecure -> autoPin", ai.autoPin)

    val many = LinkParser.parseAll("trojan://p@a.com:1#a\nhttp://nope\nvless://u@b.com:2#b\nvless://broken")
    check("parseAll counts", many.servers.size == 2 && many.failed.size == 2)

    val srv = pinned.copy(autoPin = true, path = "/a\"b\\c\n")
    val back = Server.fromMap(Json.parse(Json.write(srv.toMap())) as Map<*, *>)
    check("server round trip", back == srv)
    check("json escape round trip", (Json.parse(Json.write(mapOf("k" to "é\u0001\""))) as Map<*, *>)["k"] == "é\u0001\"")

    for (sv in listOf(t, v, vw, vm, s1, s2)) {
        check("builds ${sv.protocol}/${sv.network}", Json.parse(ConfigFactory.full(sv, null, false)) is Map<*, *>)
    }

    // ---- fragment + log file
    val fr = Json.parse(ConfigFactory.full(t.copy(fragment = true), null, false, null, "/data/x.log", "info")) as Map<String, Any?>
    val frOuts = fr["outbounds"] as List<Map<String, Any?>>
    check("fragment: proxy first then fragment outbound", frOuts[0]["tag"] == "proxy" && frOuts[1]["tag"] == "fragment" && frOuts[1]["protocol"] == "freedom")
    check("fragment: proxy dials through it", (((frOuts[0]["streamSettings"] as Map<String, Any?>)["sockopt"]) as Map<String, Any?>)["dialerProxy"] == "fragment")
    check("log file configured", ((fr["log"] as Map<String, Any?>)["error"]) == "/data/x.log" && ((fr["log"] as Map<String, Any?>)["loglevel"]) == "info")
    val nofr = Json.parse(ConfigFactory.full(t, null, false)) as Map<String, Any?>
    check("no fragment by default", (nofr["outbounds"] as List<Map<String, Any?>>).none { it["tag"] == "fragment" })
    check("fragment survives round trip", Server.fromMap(Json.parse(Json.write(t.copy(fragment = true, sub = "s1").toMap())) as Map<*, *>).let { it.fragment && it.sub == "s1" })

    // ---- subscriptions
    val links = "trojan://p@a.com:1#a\nvless://u@b.com:2?security=none#b\n"
    val b64 = java.util.Base64.getEncoder().encodeToString(links.toByteArray())
    check("sub: plain body", Subs.parse(links, "S").size == 2)
    check("sub: base64 body", Subs.parse(b64, "S").size == 2 && Subs.parse(b64, "S").all { it.sub == "S" })
    val first = Subs.parse(links, "S")
    val manual = LinkParser.parseOne("trojan://m@m.com:9#manual")
    val state1 = listOf(manual) + first.mapIndexed { i, x -> if (i == 0) x.copy(pin = "abcd") else x }
    val newBody = "trojan://p@a.com:1#renamed\nvless://u@c.com:3?security=none#c\n"
    val state2 = Subs.merge(state1, "S", Subs.parse(newBody, "S"))
    check("merge: manual servers untouched", state2.first() == manual)
    check("merge: keeps id and pin of unchanged server", state2.any { it.address == "a.com" && it.id == first[0].id && it.pin == "abcd" && it.name == "renamed" })
    check("merge: removed server is gone, new one added", state2.none { it.address == "b.com" } && state2.any { it.address == "c.com" })
    val dup = Subs.merge(emptyList(), "S", Subs.parse("trojan://p@a.com:1#x\ntrojan://p@a.com:1#y\n", "S"))
    check("merge: duplicate servers get different ids", dup.size == 2 && dup[0].id != dup[1].id)
    val subRt = Subscription.fromMap(Json.parse(Json.write(Subscription(name = "n", url = "https://x/y", updated = 5L).toMap())) as Map<*, *>)
    check("subscription round trip", subRt.name == "n" && subRt.url == "https://x/y" && subRt.updated == 5L)

    // ---- certificate name matching
    check("host match exact", CertFetcher.matchHost("de.connfull.org", "DE.connfull.org"))
    check("host match wildcard one label", CertFetcher.matchHost("*.example.com", "a.example.com") && !CertFetcher.matchHost("*.example.com", "a.b.example.com") && !CertFetcher.matchHost("*.example.com", "example.com"))
    check("host mismatch", !CertFetcher.matchHost("de.connfull.org", "www.speedtest.net"))

    // ---- diagnose against local sockets
    val plain = java.net.ServerSocket(0)
    val th = Thread { try { while (true) { val c = plain.accept(); Thread.sleep(300); c.close() } } catch (e: Exception) {} }
    th.isDaemon = true; th.start()
    val local = t.copy(address = "127.0.0.1", port = plain.localPort, sni = "x.test")
    val d1 = Diagnose.run(local) { true to "186 ms" }
    check("diagnose: dns skipped for ip, tcp ok", d1[0].ok == null && d1[1].ok == true)
    check("diagnose: tls fails on a plain server", d1[2].ok == false && d1[3].ok == true)
    check("diagnose: verdict for tls failure with working tunnel", Diagnose.verdict(d1) == "Everything works.")
    val d2 = Diagnose.run(local) { false to "timeout" }
    check("diagnose: verdict tls", Diagnose.verdict(d2).contains("TLS handshake fails"))
    val closed = java.net.ServerSocket(0).let { val p = it.localPort; it.close(); p }
    val d3 = Diagnose.run(local.copy(port = closed)) { true to "x" }
    check("diagnose: closed port stops at tcp", d3[1].ok == false && d3[2].ok == null && Diagnose.verdict(d3).contains("not reachable"))
    val d4 = Diagnose.run(local.copy(address = "no-such-host.invalid")) { true to "x" }
    check("diagnose: dns failure", d4[0].ok == false && Diagnose.verdict(d4).contains("resolved"))
    check("diagnose: format has all steps", Diagnose.format(d1).let { it.contains("[OK]") && it.contains("[FAIL]") && it.contains("Tunnel") })
    plain.close()

    println(if (failures == 0) "ALL OK" else "$failures FAILED")
    if (failures != 0) System.exit(1)
}
