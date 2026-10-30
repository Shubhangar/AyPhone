package com.droidlink.app.service

import android.app.ActivityOptions
import android.app.Notification
import android.app.PendingIntent
import android.app.Person
import android.app.RemoteInput
import android.content.Intent
import android.graphics.Bitmap
import android.os.Build
import android.os.Bundle
import android.os.Parcelable
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Base64
import android.util.Log
import androidx.core.graphics.drawable.toBitmap
import androidx.core.os.BundleCompat
import com.droidlink.app.feature.Media
import com.droidlink.app.link.Link
import com.droidlink.app.link.msg
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.concurrent.ConcurrentHashMap

/** Mirrors notifications to the Mac and carries out replies, dismissals and actions sent back. */
class PhoneNotificationListener : NotificationListenerService() {
    companion object {
        @Volatile var instance: PhoneNotificationListener? = null
            private set
    }

    private val iconCache = ConcurrentHashMap<String, String>()
    private val labelCache = ConcurrentHashMap<String, String>()
    /** Latest message forwarded per notification, to tell a new message from a refresh or our own reply. */
    private val lastMessage = ConcurrentHashMap<String, String>()

    override fun onListenerConnected() {
        instance = this
        Media.attach(this)
        sendAll()
    }

    override fun onListenerDisconnected() {
        instance = null
        Media.detach()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        if (sbn.notification.category == Notification.CATEGORY_CALL) sendCallerHint(sbn)
        if (!shouldForward(sbn)) return
        // Chat apps keep one notification per conversation and update it for every message,
        // including the echo of a reply sent from the Mac. Alert only for a new incoming message.
        val latest = latestMessage(sbn.notification)
        val previous = lastMessage.put(sbn.key, latest.text)
        val alert = previous == null || (latest.text != previous && !latest.fromMe)
        Link.send(toMessage(sbn, silent = false).put("alert", alert))
    }

    /**
     * The dialer's own call notification names the caller even when Android withholds the
     * incoming number from us (no call-log permission, or OEM quirks), so pass that along.
     */
    private fun sendCallerHint(sbn: StatusBarNotification) {
        val title = sbn.notification.extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.trim()
        if (!title.isNullOrEmpty()) Link.send(msg("callerInfo").put("name", title))
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        lastMessage.remove(sbn.key)
        if (shouldForward(sbn)) Link.send(msg("notificationRemoved").put("key", sbn.key))
    }

    /** Sends everything currently in the shade, e.g. right after connecting. The Mac lists these without banners. */
    fun sendAll() {
        if (!Link.isConnected) return
        try {
            activeNotifications.orEmpty()
                .filter(::shouldForward)
                .sortedBy { it.postTime }
                .forEach {
                    lastMessage[it.key] = latestMessage(it.notification).text
                    Link.send(toMessage(it, silent = true).put("alert", false))
                }
        } catch (e: Exception) {
            Log.w("AyPhone", "sendAll: $e")
        }
    }

    fun reply(key: String, text: String) {
        val action = find(key)?.notification?.actions?.firstOrNull(::isReplyAction) ?: return
        val results = Bundle()
        action.remoteInputs.forEach { results.putCharSequence(it.resultKey, text) }
        val intent = Intent()
        RemoteInput.addResultsToIntent(action.remoteInputs, intent, results)
        send(action.actionIntent, intent)
    }

    fun dismiss(key: String) {
        try { cancelNotification(key) } catch (_: Exception) {}
    }

    fun runAction(key: String, index: Int) {
        val actions = find(key)?.notification?.actions?.filterNot(::isReplyAction) ?: return
        actions.getOrNull(index)?.actionIntent?.let { send(it, null) }
    }

    /**
     * Fires a notification's PendingIntent. Call actions like WhatsApp's "Answer" open an
     * activity, which Android 14+ only allows from the background when the sender opts in
     * (and holds "Display over other apps").
     */
    private fun send(pi: PendingIntent, fillIn: Intent?) {
        val options = if (Build.VERSION.SDK_INT >= 34) {
            ActivityOptions.makeBasic()
                .setPendingIntentBackgroundActivityStartMode(ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED)
                .toBundle()
        } else {
            null
        }
        try {
            pi.send(this, 0, fillIn, null, null, null, options)
        } catch (e: PendingIntent.CanceledException) {
            Log.w("AyPhone", "notification action failed: $e")
        }
    }

    private fun find(key: String): StatusBarNotification? =
        try { activeNotifications?.firstOrNull { it.key == key } } catch (_: Exception) { null }

    private fun isReplyAction(a: Notification.Action) = a.remoteInputs?.any { it.allowFreeFormInput } == true

    private fun shouldForward(sbn: StatusBarNotification): Boolean {
        val n = sbn.notification
        if (sbn.packageName == packageName) return false
        // Calls from any app (dialer, WhatsApp, Telegram…) are ongoing but are exactly what we want.
        if (isCall(sbn)) return true
        if (com.droidlink.app.feature.NotificationPause.paused.value) return false
        // Other ongoing ones are media players, downloads, timers… handled elsewhere or just noise.
        if (sbn.isOngoing || !sbn.isClearable) return false
        if (n.flags and Notification.FLAG_GROUP_SUMMARY != 0) return false
        val e = n.extras
        return !e.getCharSequence(Notification.EXTRA_TITLE).isNullOrBlank() ||
            !e.getCharSequence(Notification.EXTRA_TEXT).isNullOrBlank()
    }

    private class LatestMessage(val text: String, val fromMe: Boolean)

    /** The newest message in a chat (MessagingStyle) notification, or its plain text otherwise. */
    private fun latestMessage(n: Notification): LatestMessage {
        val e = n.extras
        val last = BundleCompat.getParcelableArray(e, Notification.EXTRA_MESSAGES, Parcelable::class.java)
            ?.lastOrNull() as? Bundle
        if (last != null) {
            val text = last.getCharSequence("text")?.toString().orEmpty()
            val sender = BundleCompat.getParcelable(last, "sender_person", Person::class.java)?.name
                ?: last.getCharSequence("sender")
            val self = BundleCompat.getParcelable(e, Notification.EXTRA_MESSAGING_PERSON, Person::class.java)?.name
                ?: e.getCharSequence(Notification.EXTRA_SELF_DISPLAY_NAME)
            // In MessagingStyle, a message without a sender is one the user sent.
            val fromMe = sender == null || (self != null && sender.toString() == self.toString())
            return LatestMessage(text, fromMe)
        }
        val text = (e.getCharSequence(Notification.EXTRA_BIG_TEXT) ?: e.getCharSequence(Notification.EXTRA_TEXT))?.toString().orEmpty()
        return LatestMessage(text, false)
    }

    private fun isCall(sbn: StatusBarNotification) = sbn.notification.category == Notification.CATEGORY_CALL

    /** "incoming" while ringing, "ongoing" once answered (Android 12+ call style, else guessed from the buttons). */
    private fun callType(n: Notification): String {
        when (n.extras.getInt("android.callType", 0)) {
            1, 3 -> return "incoming" // CALL_TYPE_INCOMING, CALL_TYPE_SCREENING
            2 -> return "ongoing"
        }
        val labels = n.actions.orEmpty().joinToString(" ") { it.title?.toString().orEmpty() }.lowercase()
        return if (Regex("answer|accept|pick up").containsMatchIn(labels)) "incoming" else "ongoing"
    }

    private fun toMessage(sbn: StatusBarNotification, silent: Boolean): JSONObject {
        val n = sbn.notification
        val e = n.extras
        val actions = n.actions.orEmpty()
        val buttons = JSONArray()
        actions.filterNot(::isReplyAction).forEach { buttons.put(it.title?.toString() ?: "") }
        return msg("notification")
            .put("key", sbn.key)
            .put("pkg", sbn.packageName)
            .put("app", appLabel(sbn.packageName))
            .put("title", e.getCharSequence(Notification.EXTRA_TITLE)?.toString() ?: "")
            .put("text", (e.getCharSequence(Notification.EXTRA_BIG_TEXT) ?: e.getCharSequence(Notification.EXTRA_TEXT))?.toString() ?: "")
            .put("time", sbn.postTime)
            .put("canReply", actions.any(::isReplyAction))
            .put("actions", buttons)
            .put("icon", appIcon(sbn.packageName))
            .put("silent", silent && !isCall(sbn))
            .apply { if (isCall(sbn)) put("call", true).put("callType", callType(n)) }
    }

    private fun appLabel(pkg: String): String = labelCache.getOrPut(pkg) {
        try {
            packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString()
        } catch (_: Exception) {
            pkg
        }
    }

    private fun appIcon(pkg: String): String? = iconCache.getOrPut(pkg) {
        try {
            val bmp = packageManager.getApplicationIcon(pkg).toBitmap(96, 96)
            val bytes = ByteArrayOutputStream().also { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
            Base64.encodeToString(bytes, Base64.NO_WRAP)
        } catch (_: Exception) {
            ""
        }
    }.ifEmpty { null }
}
