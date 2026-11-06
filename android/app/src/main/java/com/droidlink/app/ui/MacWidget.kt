package com.droidlink.app.ui

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.util.SizeF
import android.widget.RemoteViews
import com.droidlink.app.R
import com.droidlink.app.feature.MacControl
import com.droidlink.app.link.Link
import com.droidlink.app.link.msg

/**
 * Home-screen widget. One row: the Mac, its battery and four quick actions (send files, lock,
 * play/pause, mute). Resized to two rows or more: six actions and big controls for the Mac's media.
 * Actions that need no screen go straight to the Mac from here.
 */
class MacWidget : AppWidgetProvider() {
    override fun onUpdate(ctx: Context, manager: AppWidgetManager, ids: IntArray) = update(ctx)

    override fun onAppWidgetOptionsChanged(ctx: Context, manager: AppWidgetManager, id: Int, options: Bundle) = update(ctx)

    override fun onReceive(ctx: Context, intent: Intent) {
        when (intent.action) {
            ACTION_LOCK -> MacControl.lock()
            ACTION_PLAY -> MacControl.media("playPause")
            ACTION_NEXT -> MacControl.media("next")
            ACTION_PREVIOUS -> MacControl.media("previous")
            ACTION_FIND -> MacControl.find(!MacControl.finding.value)
            ACTION_SHARE -> Link.send(msg("mirrorRequest").put("on", true))
            ACTION_MUTE -> {
                val on = Link.macMuted.value != true
                Link.macMuted.value = on
                Link.send(msg("macMute").put("on", on))
                update(ctx)
            }
            else -> super.onReceive(ctx, intent)
        }
    }

    companion object {
        const val ACTION_PICK_FILES = "com.droidlink.app.PICK_FILES"
        private const val ACTION_LOCK = "com.droidlink.app.widget.LOCK"
        private const val ACTION_PLAY = "com.droidlink.app.widget.PLAY"
        private const val ACTION_NEXT = "com.droidlink.app.widget.NEXT"
        private const val ACTION_PREVIOUS = "com.droidlink.app.widget.PREVIOUS"
        private const val ACTION_MUTE = "com.droidlink.app.widget.MUTE"
        private const val ACTION_FIND = "com.droidlink.app.widget.FIND"
        private const val ACTION_SHARE = "com.droidlink.app.widget.SHARE"
        /** Heights (dp) from which the two-row layout is used. */
        private const val LARGE_HEIGHT = 120f

        /** Redraws every placed widget from the link's current state. */
        fun update(ctx: Context) {
            val manager = AppWidgetManager.getInstance(ctx)
            val ids = manager.getAppWidgetIds(ComponentName(ctx, MacWidget::class.java))
            if (ids.isEmpty()) return
            val small = views(ctx, R.layout.widget_mac_small)
            val large = views(ctx, R.layout.widget_mac)
            for (id in ids) {
                if (Build.VERSION.SDK_INT >= 31) {
                    // The launcher picks the layout that fits as the widget is resized.
                    manager.updateAppWidget(id, RemoteViews(mapOf(SizeF(200f, 40f) to small, SizeF(200f, LARGE_HEIGHT) to large)))
                } else {
                    val height = manager.getAppWidgetOptions(id).getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT)
                    manager.updateAppWidget(id, if (height >= LARGE_HEIGHT) large else small)
                }
            }
        }

        private fun views(ctx: Context, layout: Int) = RemoteViews(ctx.packageName, layout).apply {
            val connected = Link.state.value == Link.State.Connected
            val battery = Link.macBattery.value
            setTextViewText(R.id.widget_name, Link.macName.value ?: "Your Mac")
            setTextViewText(
                R.id.widget_status,
                when (Link.state.value) {
                    Link.State.Connected -> "Connected · ${Link.transport.value ?: "Wi-Fi"}"
                    Link.State.Connecting -> "Looking for your Mac…"
                    Link.State.Unpaired -> "Not paired"
                    Link.State.Disconnected -> "Not connected"
                }
            )
            setTextViewText(R.id.widget_battery, if (connected && battery != null) "${battery.level}%" else "")
            setTextColor(
                R.id.widget_battery,
                when {
                    battery == null -> 0
                    battery.charging -> 0xFF33D67A.toInt()
                    battery.level <= 15 -> 0xFFFF4545.toInt()
                    else -> 0xFFEE90FF.toInt()
                }
            )
            val playing = Link.macNowPlaying.value.playing
            setImageViewResource(R.id.widget_play, if (playing) R.drawable.ic_w_pause else R.drawable.ic_w_play)
            val muted = Link.macMuted.value == true
            val muteIcon = if (muted) R.drawable.ic_w_volume_off else R.drawable.ic_w_volume_up
            if (layout == R.layout.widget_mac_small) {
                setImageViewResource(R.id.widget_mute, muteIcon)
                setInt(R.id.widget_mute, "setBackgroundResource", if (muted) R.drawable.widget_button_on_bg else R.drawable.widget_button_bg)
            } else {
                setImageViewResource(R.id.widget_mute_icon, muteIcon)
                setTextViewText(R.id.widget_mute_label, if (muted) "Muted" else "Mute")
                setInt(R.id.widget_mute, "setBackgroundResource", if (muted) R.drawable.widget_button_on_bg else R.drawable.widget_button_bg)
                val np = Link.macNowPlaying.value
                setTextViewText(
                    R.id.widget_media_title,
                    listOf(np.title, np.artist).filter { it.isNotEmpty() }.joinToString(" · ").ifEmpty { "Mac media" }
                )
                setOnClickPendingIntent(R.id.widget_share, broadcast(ctx, ACTION_SHARE))
                setOnClickPendingIntent(R.id.widget_find, broadcast(ctx, ACTION_FIND))
                setOnClickPendingIntent(R.id.widget_next, broadcast(ctx, ACTION_NEXT))
                setOnClickPendingIntent(R.id.widget_previous, broadcast(ctx, ACTION_PREVIOUS))
                setOnClickPendingIntent(
                    R.id.widget_trackpad,
                    PendingIntent.getActivity(ctx, 4, Intent(ctx, TrackpadActivity::class.java), FLAGS)
                )
            }
            setOnClickPendingIntent(R.id.widget_root, PendingIntent.getActivity(ctx, 0, Intent(ctx, MainActivity::class.java), FLAGS))
            setOnClickPendingIntent(
                R.id.widget_files,
                PendingIntent.getActivity(ctx, 2, Intent(ctx, MainActivity::class.java).setAction(ACTION_PICK_FILES), FLAGS)
            )
            setOnClickPendingIntent(R.id.widget_lock, broadcast(ctx, ACTION_LOCK))
            setOnClickPendingIntent(R.id.widget_play, broadcast(ctx, ACTION_PLAY))
            setOnClickPendingIntent(R.id.widget_mute, broadcast(ctx, ACTION_MUTE))
        }

        private const val FLAGS = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT

        private fun broadcast(ctx: Context, action: String) = PendingIntent.getBroadcast(
            ctx, action.hashCode(), Intent(ctx, MacWidget::class.java).setAction(action), FLAGS
        )
    }
}
