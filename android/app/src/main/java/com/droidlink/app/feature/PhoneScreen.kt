package com.droidlink.app.feature

import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.PowerManager
import com.droidlink.app.link.Link
import com.droidlink.app.link.msg

/**
 * Tells the Mac whether the phone is in use (screen on and unlocked). The Mac only fetches the
 * phone's clipboard on ⌘V while it is, since Android shares the clipboard only then, and uses
 * the timing to tell whether the phone or the Mac was used last.
 */
object PhoneScreen {
    /** "unlocked", "locked" (screen on, keyguard up) or "off". */
    fun current(ctx: Context): String = when {
        !ctx.getSystemService(PowerManager::class.java).isInteractive -> "off"
        ctx.getSystemService(KeyguardManager::class.java).isKeyguardLocked -> "locked"
        else -> "unlocked"
    }

    fun send(ctx: Context) {
        if (Link.isConnected) Link.send(msg("phoneScreen").put("action", current(ctx)))
    }

    val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) = send(context)
    }
}
