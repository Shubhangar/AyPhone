package com.droidlink.app.feature

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONArray
import org.json.JSONObject

/** Recent clipboard and file transfers, for the Activity card (kept across restarts). */
object ActivityLog {
    data class Entry(val text: String, val detail: String?, val at: Long)

    private const val PREFS = "activity"
    private const val MAX = 4
    private lateinit var ctx: Context
    val entries = MutableStateFlow<List<Entry>>(emptyList())

    fun init(context: Context) {
        ctx = context.applicationContext
        val saved = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("entries", null) ?: return
        entries.value = try {
            JSONArray(saved).let { a ->
                (0 until a.length()).map { i ->
                    val o = a.getJSONObject(i)
                    Entry(o.getString("text"), o.optString("detail").ifEmpty { null }, o.getLong("at"))
                }
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun add(text: String, detail: String? = null) {
        if (!::ctx.isInitialized) return
        val list = (listOf(Entry(text, detail, System.currentTimeMillis())) + entries.value).take(MAX)
        entries.value = list
        val json = JSONArray(list.map { JSONObject().put("text", it.text).put("detail", it.detail ?: "").put("at", it.at) })
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString("entries", json.toString()).apply()
    }
}

/** "Pause notifications" on the phone: stops sending new ones to the Mac (calls still go through). */
object NotificationPause {
    private const val PREFS = "settings"
    private lateinit var ctx: Context
    val paused = MutableStateFlow(false)

    fun init(context: Context) {
        ctx = context.applicationContext
        paused.value = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean("notificationsPaused", false)
    }

    fun set(value: Boolean) {
        paused.value = value
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean("notificationsPaused", value).apply()
    }
}
