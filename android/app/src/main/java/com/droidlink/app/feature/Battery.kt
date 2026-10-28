package com.droidlink.app.feature

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import com.droidlink.app.link.Link
import com.droidlink.app.link.msg

object Battery {
    private var lastLevel = -1
    private var lastCharging: Boolean? = null

    val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) = update(intent, force = false)
    }

    fun sendCurrent(ctx: Context) {
        update(ctx.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)), force = true)
    }

    @Synchronized
    private fun update(intent: Intent?, force: Boolean) {
        intent ?: return
        val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, 100).coerceAtLeast(1)
        val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) * 100 / scale
        val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        val charging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
        if (!force && level == lastLevel && charging == lastCharging) return
        lastLevel = level
        lastCharging = charging
        Link.send(msg("battery").put("level", level).put("charging", charging))
    }
}
