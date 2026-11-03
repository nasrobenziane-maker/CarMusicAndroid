package com.codex.carmusic

import android.content.Context
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Small persistent log of Bluetooth and playback events, shown from the app to diagnose problems. */
internal object EventLog {
    private const val KEY = "event_log"
    private const val MAX_LINES = 80

    fun add(context: Context, message: String) {
        val prefs = context.getSharedPreferences(AppPrefs.FILE, Context.MODE_PRIVATE)
        synchronized(this) {
            val stamp = SimpleDateFormat("MM-dd HH:mm:ss", Locale.US).format(Date())
            val lines = prefs.getString(KEY, "").orEmpty().split('\n').filter { it.isNotBlank() }
            val updated = (lines + "$stamp  $message").takeLast(MAX_LINES)
            prefs.edit().putString(KEY, updated.joinToString("\n")).apply()
        }
    }

    /** Newest event first. */
    fun read(context: Context): String = context.getSharedPreferences(AppPrefs.FILE, Context.MODE_PRIVATE)
        .getString(KEY, "").orEmpty().split('\n').filter { it.isNotBlank() }.reversed().joinToString("\n")

    fun clear(context: Context) {
        context.getSharedPreferences(AppPrefs.FILE, Context.MODE_PRIVATE).edit().remove(KEY).apply()
    }
}
