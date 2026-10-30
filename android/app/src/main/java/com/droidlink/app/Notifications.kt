package com.droidlink.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import android.net.Uri
import com.droidlink.app.link.Link
import com.droidlink.app.ui.ClipboardActivity
import com.droidlink.app.ui.MainActivity
import java.util.concurrent.atomic.AtomicInteger

object Notifications {
    private const val STATUS = "status"
    private const val EVENTS = "events"
    const val STATUS_ID = 1
    const val MIRROR_ID = 2
    private val nextId = AtomicInteger(100)

    fun createChannels(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(STATUS, "Connection status", NotificationManager.IMPORTANCE_MIN)
        )
        nm.createNotificationChannel(
            NotificationChannel(EVENTS, "Files and links from your Mac", NotificationManager.IMPORTANCE_HIGH)
        )
    }

    fun status(ctx: Context, state: Link.State, macName: String?, transport: String?): Notification {
        val text = when (state) {
            Link.State.Connected -> "Connected to ${macName ?: "your Mac"}" + (transport?.let { " over $it" } ?: "")
            Link.State.Connecting -> "Looking for your Mac…"
            Link.State.Unpaired -> "Not paired. Open AyPhone to pair."
            Link.State.Disconnected -> "Disconnected"
        }
        val open = PendingIntent.getActivity(
            ctx, 0, Intent(ctx, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        val clip = PendingIntent.getActivity(
            ctx, 1,
            Intent(ctx, ClipboardActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE
        )
        return Notification.Builder(ctx, STATUS)
            .setSmallIcon(R.drawable.ic_stat_ayphone)
            .setContentTitle("AyPhone")
            .setContentText(text)
            .setContentIntent(open)
            .setOngoing(true)
            .setShowWhen(false)
            .addAction(
                Notification.Action.Builder(
                    Icon.createWithResource(ctx, R.drawable.ic_clipboard), "Send clipboard", clip
                ).build()
            )
            .build()
    }

    fun updateStatus(ctx: Context, notification: Notification) {
        ctx.getSystemService(NotificationManager::class.java).notify(STATUS_ID, notification)
    }

    fun showFile(ctx: Context, uri: Uri, name: String, mime: String) {
        val view = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, mime)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        post(ctx, "Received from Mac", name, view)
    }

    /** Android blocks background activity starts, so links from the Mac arrive as a tap-to-open notification. */
    fun showLink(ctx: Context, url: String) {
        val uri = Uri.parse(url)
        if (uri.scheme != "http" && uri.scheme != "https") return
        post(ctx, "Link from Mac", url, Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    /** Ongoing notification while the screen is shared with the Mac, with a Stop button. */
    fun mirroring(ctx: Context): Notification {
        val stop = PendingIntent.getService(
            ctx, 3,
            Intent(ctx, com.droidlink.app.service.MirrorService::class.java).setAction(com.droidlink.app.service.MirrorService.ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE
        )
        return Notification.Builder(ctx, STATUS)
            .setSmallIcon(R.drawable.ic_stat_ayphone)
            .setContentTitle("Mirroring to your Mac")
            .setContentText("Your screen is visible on your Mac")
            .setOngoing(true)
            .addAction(Notification.Action.Builder(null, "Stop", stop).build())
            .build()
    }

    /** Fallback when AyPhone can't open the sharing prompt itself (no "Display over other apps"). */
    fun showMirrorPrompt(ctx: Context, intent: Intent) {
        post(ctx, "Mirror to your Mac", "Tap to share your screen with your Mac", intent)
    }

    fun showLaunch(ctx: Context, appName: String, intent: Intent) {
        post(ctx, "Open $appName", "Your Mac asked to open $appName. Allow \"Display over other apps\" to skip this tap.", intent)
    }

    /** An alert from AyPhone itself (the Mac's battery…); tapping it opens the app. */
    fun alert(ctx: Context, title: String, text: String) {
        post(ctx, title, text, Intent(ctx, com.droidlink.app.ui.MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    private fun post(ctx: Context, title: String, text: String, intent: Intent) {
        val id = nextId.incrementAndGet()
        val pi = PendingIntent.getActivity(ctx, id, intent, PendingIntent.FLAG_IMMUTABLE)
        val n = Notification.Builder(ctx, EVENTS)
            .setSmallIcon(R.drawable.ic_stat_ayphone)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(pi)
            .setAutoCancel(true)
            .build()
        ctx.getSystemService(NotificationManager::class.java).notify(id, n)
    }
}
