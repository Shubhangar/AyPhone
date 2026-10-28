package com.droidlink.app.feature

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.telecom.TelecomManager
import android.telephony.TelephonyManager
import android.util.Log
import com.droidlink.app.link.Link
import com.droidlink.app.link.msg

/** Call state goes to the Mac; call audio and the microphone stay on the phone. */
object Calls {
    fun onPhoneState(ctx: Context, intent: Intent) {
        val state = when (intent.getStringExtra(TelephonyManager.EXTRA_STATE)) {
            TelephonyManager.EXTRA_STATE_RINGING -> "ringing"
            TelephonyManager.EXTRA_STATE_OFFHOOK -> "offhook"
            else -> "idle"
        }
        val m = msg("call").put("state", state)
        // Only present when READ_CALL_LOG is granted; Android then sends this broadcast twice.
        @Suppress("DEPRECATION")
        val number = intent.getStringExtra(TelephonyManager.EXTRA_INCOMING_NUMBER)
        if (!number.isNullOrEmpty()) {
            m.put("number", number)
            Sms.contactName(ctx, number)?.let { m.put("name", it) }
        }
        Link.send(m)
    }

    @SuppressLint("MissingPermission")
    @Suppress("DEPRECATION")
    fun perform(ctx: Context, action: String) {
        val telecom = ctx.getSystemService(TelecomManager::class.java)
        try {
            when (action) {
                "answer" -> telecom.acceptRingingCall()
                "decline", "end" -> telecom.endCall()
                "mute" -> {
                    val audio = ctx.getSystemService(AudioManager::class.java)
                    audio.isMicrophoneMute = !audio.isMicrophoneMute
                }
            }
        } catch (e: SecurityException) {
            Log.w("AyPhone", "call action $action needs the phone permission: $e")
        }
    }
}
