package com.droidlink.app.ui

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Debug builds only: `adb shell am broadcast -n com.droidlink.app/.ui.GlowTestReceiver [--ez receiving true]`. */
class GlowTestReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        TransferGlow.show(context, intent.getBooleanExtra("receiving", true), intent.getBooleanExtra("ignoreLock", false))
    }
}
