package com.droidlink.app.service

import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.droidlink.app.link.Link
import com.droidlink.app.ui.ClipboardActivity

/** Quick Settings tile: one tap sends the phone's clipboard to the Mac. */
class ClipboardTileService : TileService() {
    override fun onStartListening() {
        qsTile?.apply {
            state = if (Link.isConnected) Tile.STATE_INACTIVE else Tile.STATE_UNAVAILABLE
            updateTile()
        }
    }

    override fun onClick() {
        val intent = Intent(this, ClipboardActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (Build.VERSION.SDK_INT >= 34) {
            startActivityAndCollapse(PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE))
        } else {
            startLegacy(intent)
        }
    }

    @Suppress("DEPRECATION")
    private fun startLegacy(intent: Intent) = startActivityAndCollapse(intent)
}
