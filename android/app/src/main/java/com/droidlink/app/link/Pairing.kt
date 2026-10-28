package com.droidlink.app.link

import android.content.Context
import android.net.Uri
import android.util.Base64
import java.util.UUID

class PairingInfo(val key: ByteArray, val hosts: List<String>, val port: Int, val macName: String)

/** Stores what the Mac's pairing QR code contained (see PROTOCOL.md). */
object Pairing {
    private const val PREFS = "pairing"

    fun parse(text: String): PairingInfo? {
        val uri = Uri.parse(text.trim())
        if (uri.scheme != "droidlink" || uri.host != "pair") return null
        val key = try {
            Base64.decode(uri.getQueryParameter("k") ?: return null, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
        } catch (e: IllegalArgumentException) {
            return null
        }
        if (key.size != 32) return null
        val hosts = uri.getQueryParameter("h").orEmpty().split(',').map { it.trim() }.filter { it.isNotEmpty() }
        val port = uri.getQueryParameter("p")?.toIntOrNull() ?: 47800
        return PairingInfo(key, hosts, port, uri.getQueryParameter("n") ?: "Mac")
    }

    fun save(ctx: Context, info: PairingInfo) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString("key", Base64.encodeToString(info.key, Base64.NO_WRAP))
            .putString("hosts", info.hosts.joinToString(","))
            .putInt("port", info.port)
            .putString("name", info.macName)
            .apply()
    }

    fun load(ctx: Context): PairingInfo? {
        val p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val key = p.getString("key", null) ?: return null
        return PairingInfo(
            Base64.decode(key, Base64.NO_WRAP),
            p.getString("hosts", "").orEmpty().split(',').filter { it.isNotEmpty() },
            p.getInt("port", 47800),
            p.getString("name", "Mac") ?: "Mac"
        )
    }

    fun clear(ctx: Context) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .remove("key").remove("hosts").remove("port").remove("name").apply()
    }

    /** Remembers an address the Mac was reached at (newest first), for instant reconnects on that network. */
    fun rememberHost(ctx: Context, host: String) {
        val p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val hosts = p.getString("hosts", "").orEmpty().split(',').filter { it.isNotEmpty() && it != host }
        p.edit().putString("hosts", (listOf(host) + hosts).take(8).joinToString(",")).apply()
    }

    fun deviceId(ctx: Context): String {
        val p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return p.getString("deviceId", null) ?: UUID.randomUUID().toString().also {
            p.edit().putString("deviceId", it).apply()
        }
    }
}
