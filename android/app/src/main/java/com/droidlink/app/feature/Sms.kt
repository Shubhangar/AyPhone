package com.droidlink.app.feature

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.ContactsContract
import android.provider.Telephony
import android.telephony.SmsManager
import android.util.Log
import androidx.core.content.ContextCompat
import com.droidlink.app.link.msg
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

object Sms {
    private const val MAX_THREADS = 60
    private const val MAX_ROWS = 3_000
    private const val MAX_MESSAGES = 200

    private val nameCache = ConcurrentHashMap<String, String>()

    private fun has(ctx: Context, permission: String) =
        ContextCompat.checkSelfPermission(ctx, permission) == PackageManager.PERMISSION_GRANTED

    /** Most recent conversations, built from the newest message of each thread. */
    fun threadsMessage(ctx: Context): JSONObject {
        val threads = JSONArray()
        if (has(ctx, Manifest.permission.READ_SMS)) {
            val seen = HashSet<Long>()
            val projection = arrayOf(
                Telephony.Sms.THREAD_ID, Telephony.Sms.ADDRESS, Telephony.Sms.BODY, Telephony.Sms.DATE, Telephony.Sms.READ
            )
            ctx.contentResolver.query(Telephony.Sms.CONTENT_URI, projection, null, null, "${Telephony.Sms.DATE} DESC")?.use { c ->
                while (c.moveToNext() && seen.size < MAX_THREADS && c.position < MAX_ROWS) {
                    val threadId = c.getLong(0)
                    if (!seen.add(threadId)) continue
                    val address = c.getString(1) ?: continue
                    threads.put(
                        JSONObject()
                            .put("id", threadId)
                            .put("address", address)
                            .put("name", contactName(ctx, address))
                            .put("snippet", c.getString(2) ?: "")
                            .put("date", c.getLong(3))
                            .put("unread", c.getInt(4) == 0)
                    )
                }
            }
        }
        return msg("smsThreads").put("threads", threads)
    }

    fun messagesMessage(ctx: Context, threadId: Long): JSONObject {
        val list = ArrayList<JSONObject>()
        if (has(ctx, Manifest.permission.READ_SMS)) {
            val projection = arrayOf(
                Telephony.Sms._ID, Telephony.Sms.ADDRESS, Telephony.Sms.BODY, Telephony.Sms.DATE, Telephony.Sms.TYPE
            )
            ctx.contentResolver.query(
                Telephony.Sms.CONTENT_URI, projection, "${Telephony.Sms.THREAD_ID} = ?", arrayOf(threadId.toString()),
                "${Telephony.Sms.DATE} DESC"
            )?.use { c ->
                while (c.moveToNext() && list.size < MAX_MESSAGES) {
                    list.add(
                        JSONObject()
                            .put("id", c.getLong(0))
                            .put("threadId", threadId)
                            .put("address", c.getString(1) ?: "")
                            .put("body", c.getString(2) ?: "")
                            .put("date", c.getLong(3))
                            .put("outgoing", c.getInt(4) != Telephony.Sms.MESSAGE_TYPE_INBOX)
                    )
                }
            }
        }
        return msg("smsMessages").put("threadId", threadId).put("messages", JSONArray(list.asReversed()))
    }

    fun send(ctx: Context, address: String, text: String) {
        if (address.isBlank() || text.isBlank() || !has(ctx, Manifest.permission.SEND_SMS)) return
        try {
            val sms = smsManager(ctx)
            val parts = sms.divideMessage(text)
            // The system records messages sent this way in the SMS provider, which triggers smsChanged.
            if (parts.size > 1) sms.sendMultipartTextMessage(address, null, parts, null, null)
            else sms.sendTextMessage(address, null, text, null, null)
        } catch (e: Exception) {
            Log.w("AyPhone", "SMS send failed: $e")
        }
    }

    @Suppress("DEPRECATION")
    private fun smsManager(ctx: Context): SmsManager =
        if (Build.VERSION.SDK_INT >= 31) ctx.getSystemService(SmsManager::class.java) else SmsManager.getDefault()

    fun contactName(ctx: Context, number: String): String? {
        if (!has(ctx, Manifest.permission.READ_CONTACTS)) return null
        nameCache[number]?.let { return it.ifEmpty { null } }
        val name = try {
            val uri = Uri.withAppendedPath(ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(number))
            ctx.contentResolver.query(uri, arrayOf(ContactsContract.PhoneLookup.DISPLAY_NAME), null, null, null)?.use {
                if (it.moveToFirst()) it.getString(0) else null
            }
        } catch (_: Exception) {
            null
        }
        nameCache[number] = name ?: ""
        return name
    }
}
