package com.aboezzat.vpn.data

import android.content.Context
import com.aboezzat.vpn.core.RouteMode
import org.json.JSONObject

class AppRouteStore(context: Context) {
    private val prefs = context.getSharedPreferences("app_routes", Context.MODE_PRIVATE)

    fun load(): Map<String, RouteMode> {
        val raw = prefs.getString("routes", "{}") ?: "{}"
        return try {
            val json = JSONObject(raw)
            buildMap {
                val keys = json.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    val mode = runCatching { RouteMode.valueOf(json.getString(key)) }.getOrNull()
                    if (mode != null) put(key, mode)
                }
            }
        } catch (_: Exception) {
            emptyMap()
        }
    }

    fun save(routes: Map<String, RouteMode>) {
        val json = JSONObject()
        routes.forEach { (packageName, mode) -> json.put(packageName, mode.name) }
        prefs.edit().putString("routes", json.toString()).apply()
    }
}
