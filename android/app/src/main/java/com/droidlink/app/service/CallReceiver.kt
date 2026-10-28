package com.droidlink.app.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.droidlink.app.feature.Calls

class CallReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        Calls.onPhoneState(context, intent)
    }
}
