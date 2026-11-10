package com.bat.v2

import android.util.Log
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Small in-memory log of what the app itself did (shown in the log screen). */
object AppLog {
    private val lines = ArrayDeque<String>()
    private val fmt = SimpleDateFormat("HH:mm:ss", Locale.US)

    @Synchronized fun add(msg: String) {
        lines.addLast(fmt.format(Date()) + "  " + msg)
        while (lines.size > 300) lines.removeFirst()
        Log.d("BatV2", msg)
    }

    @Synchronized fun dump(): String = lines.joinToString("\n")
    @Synchronized fun clear() = lines.clear()
}
