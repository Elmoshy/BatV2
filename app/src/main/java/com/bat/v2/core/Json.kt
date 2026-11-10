package com.bat.v2.core

/** Tiny JSON reader/writer (no Android dependency so it can be unit tested on a plain JVM). */
object Json {
    fun write(v: Any?): String = StringBuilder().also { w(v, it) }.toString()

    private fun w(v: Any?, sb: StringBuilder) {
        when (v) {
            null -> sb.append("null")
            is Boolean, is Int, is Long -> sb.append(v.toString())
            is Double -> if (v % 1.0 == 0.0 && Math.abs(v) < 1e15) sb.append(v.toLong()) else sb.append(v.toString())
            is String -> quote(v, sb)
            is Map<*, *> -> {
                sb.append('{')
                var first = true
                for ((k, x) in v) {
                    if (!first) sb.append(',')
                    first = false
                    quote(k.toString(), sb); sb.append(':'); w(x, sb)
                }
                sb.append('}')
            }
            is Iterable<*> -> {
                sb.append('[')
                var first = true
                for (x in v) {
                    if (!first) sb.append(',')
                    first = false
                    w(x, sb)
                }
                sb.append(']')
            }
            else -> quote(v.toString(), sb)
        }
    }

    private fun quote(s: String, sb: StringBuilder) {
        sb.append('"')
        for (c in s) when {
            c == '"' -> sb.append("\\\"")
            c == '\\' -> sb.append("\\\\")
            c == '\n' -> sb.append("\\n")
            c == '\r' -> sb.append("\\r")
            c == '\t' -> sb.append("\\t")
            c < ' ' -> sb.append(String.format("\\u%04x", c.code))
            else -> sb.append(c)
        }
        sb.append('"')
    }

    fun parse(text: String): Any? {
        val p = Parser(text)
        p.ws()
        val v = p.value()
        p.ws()
        if (p.i != text.length) throw IllegalArgumentException("trailing data at ${p.i}")
        return v
    }

    private class Parser(val s: String) {
        var i = 0
        fun ws() { while (i < s.length && s[i].isWhitespace()) i++ }
        fun fail(m: String): Nothing = throw IllegalArgumentException("$m at $i")

        fun value(): Any? {
            if (i >= s.length) fail("unexpected end")
            return when (val c = s[i]) {
                '{' -> obj()
                '[' -> arr()
                '"' -> str()
                't' -> lit("true", true)
                'f' -> lit("false", false)
                'n' -> lit("null", null)
                else -> if (c == '-' || c.isDigit()) num() else fail("unexpected '$c'")
            }
        }

        fun lit(w: String, v: Any?): Any? {
            if (!s.startsWith(w, i)) fail("bad literal")
            i += w.length
            return v
        }

        fun obj(): Map<String, Any?> {
            val m = LinkedHashMap<String, Any?>()
            i++; ws()
            if (s[i] == '}') { i++; return m }
            while (true) {
                ws()
                if (i >= s.length || s[i] != '"') fail("expected key")
                val k = str(); ws()
                if (i >= s.length || s[i] != ':') fail("expected ':'")
                i++; ws()
                m[k] = value(); ws()
                if (i >= s.length) fail("unterminated object")
                if (s[i] == ',') { i++; continue }
                if (s[i] == '}') { i++; return m }
                fail("expected ',' or '}'")
            }
        }

        fun arr(): List<Any?> {
            val l = ArrayList<Any?>()
            i++; ws()
            if (s[i] == ']') { i++; return l }
            while (true) {
                ws(); l.add(value()); ws()
                if (i >= s.length) fail("unterminated array")
                if (s[i] == ',') { i++; continue }
                if (s[i] == ']') { i++; return l }
                fail("expected ',' or ']'")
            }
        }

        fun str(): String {
            val sb = StringBuilder()
            i++
            while (true) {
                if (i >= s.length) fail("unterminated string")
                val c = s[i++]
                if (c == '"') return sb.toString()
                if (c != '\\') { sb.append(c); continue }
                if (i >= s.length) fail("bad escape")
                when (val e = s[i++]) {
                    '"' -> sb.append('"'); '\\' -> sb.append('\\'); '/' -> sb.append('/')
                    'n' -> sb.append('\n'); 'r' -> sb.append('\r'); 't' -> sb.append('\t')
                    'b' -> sb.append('\b'); 'f' -> sb.append('\u000c')
                    'u' -> {
                        if (i + 4 > s.length) fail("bad unicode escape")
                        sb.append(s.substring(i, i + 4).toInt(16).toChar()); i += 4
                    }
                    else -> fail("bad escape '$e'")
                }
            }
        }

        fun num(): Any {
            val st = i
            if (s[i] == '-') i++
            while (i < s.length && (s[i].isDigit() || s[i] in ".eE+-")) i++
            val t = s.substring(st, i)
            return t.toLongOrNull() ?: t.toDoubleOrNull() ?: fail("bad number")
        }
    }
}
