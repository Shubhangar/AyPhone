package com.droidlink.app.ui

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import com.droidlink.app.R
import com.droidlink.app.link.Link

/** Home-screen widget: the Mac's name, battery and connection, with Clipboard and Files buttons. */
class MacWidget : AppWidgetProvider() {
    override fun onUpdate(ctx: Context, manager: AppWidgetManager, ids: IntArray) = update(ctx)

    companion object {
        const val ACTION_PICK_FILES = "com.droidlink.app.PICK_FILES"

        /** Redraws every placed widget from the link's current state. */
        fun update(ctx: Context) {
            val manager = AppWidgetManager.getInstance(ctx)
            val ids = manager.getAppWidgetIds(ComponentName(ctx, MacWidget::class.java))
            if (ids.isEmpty()) return
            val connected = Link.state.value == Link.State.Connected
            val battery = Link.macBattery.value
            val views = RemoteViews(ctx.packageName, R.layout.widget_mac).apply {
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
                val flags = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                setOnClickPendingIntent(
                    R.id.widget_root,
                    PendingIntent.getActivity(ctx, 0, Intent(ctx, MainActivity::class.java), flags)
                )
                // The clipboard is only readable from a focused app, which ClipboardActivity briefly is.
                setOnClickPendingIntent(
                    R.id.widget_clipboard,
                    PendingIntent.getActivity(ctx, 1, Intent(ctx, ClipboardActivity::class.java), flags)
                )
                setOnClickPendingIntent(
                    R.id.widget_files,
                    PendingIntent.getActivity(ctx, 2, Intent(ctx, MainActivity::class.java).setAction(ACTION_PICK_FILES), flags)
                )
            }
            manager.updateAppWidget(ids, views)
        }
    }
}
