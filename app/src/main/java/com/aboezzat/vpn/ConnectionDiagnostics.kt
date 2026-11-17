package com.aboezzat.vpn
import android.content.Context
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
object ConnectionDiagnostics {
    private const val PREFS = "connection_diagnostics"
    private const val KEY_EVENTS = "events"
    private const val MAX_EVENTS = 100
    @Synchronized fun add(context: Context, message: String) {
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
        val clean = message.replace(Regex("[\\r\\n]+"), " ").take(260)
        val current = prefs.getString(KEY_EVENTS, "").orEmpty()
        val updated = (current.split("\n").filter { it.isNotBlank() } + "$stamp | $clean").takeLast(MAX_EVENTS).joinToString("\n")
        prefs.edit().putString(KEY_EVENTS, updated).apply()
    }
    fun read(context: Context): String = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_EVENTS, "").orEmpty()
    fun clear(context: Context) { context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(KEY_EVENTS).apply() }
}
