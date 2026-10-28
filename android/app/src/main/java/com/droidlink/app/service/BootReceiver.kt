package com.droidlink.app.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.droidlink.app.link.Pairing

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (Pairing.load(context) != null) LinkService.start(context)
    }
}
