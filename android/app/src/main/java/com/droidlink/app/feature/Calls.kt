package com.droidlink.app.feature

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.telecom.TelecomManager
import android.telephony.TelephonyManager
import android.util.Log
import com.droidlink.app.link.Link
import com.droidlink.app.link.msg

/** Call state goes to the Mac; call audio and the microphone stay on the phone. */
object Calls {
    /** Whether the Mac asked for the speaker (cleared again when the call ends). */
    private var speakerOn = false

    fun onPhoneState(ctx: Context, intent: Intent) {
        val state = when (intent.getStringExtra(TelephonyManager.EXTRA_STATE)) {
            TelephonyManager.EXTRA_STATE_RINGING -> "ringing"
            TelephonyManager.EXTRA_STATE_OFFHOOK -> "offhook"
            else -> "idle"
        }
        // Our speaker request outlives the call otherwise, and would route the next one too.
        if (state == "idle" && speakerOn) setSpeaker(ctx, false)
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
        // With the Mac approved as a companion device, Android lets AyPhone steer the call itself.
        if (CallCompanionService.instance?.perform(action) == true) return
        val telecom = ctx.getSystemService(TelecomManager::class.java)
        try {
            when (action) {
                "answer" -> telecom.acceptRingingCall()
                "decline", "end" -> telecom.endCall()
                "mute" -> {
                    val audio = ctx.getSystemService(AudioManager::class.java)
                    audio.isMicrophoneMute = !audio.isMicrophoneMute
                }
                "speaker" -> setSpeaker(ctx, !onSpeaker(ctx.getSystemService(AudioManager::class.java)))
            }
        } catch (e: SecurityException) {
            Log.w("AyPhone", "call action $action needs the phone permission: $e")
        }
    }

    /** Moves the call's sound between the earpiece and the loudspeaker, then tells the Mac
     *  where it ended up (Android may keep it elsewhere, e.g. on a Bluetooth headset). */
    @Suppress("DEPRECATION")
    private fun setSpeaker(ctx: Context, on: Boolean) {
        val audio = ctx.getSystemService(AudioManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val speaker = audio.availableCommunicationDevices.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
            if (on && speaker != null) audio.setCommunicationDevice(speaker) else audio.clearCommunicationDevice()
        } else {
            audio.isSpeakerphoneOn = on
        }
        speakerOn = on
        // The route changes a moment later.
        Handler(Looper.getMainLooper()).postDelayed({
            val actual = onSpeaker(audio)
            if (on && !actual) {
                // The calling app holds the route; withdraw our request so it can't linger.
                Log.w("AyPhone", "speaker request didn't take")
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) audio.clearCommunicationDevice()
                speakerOn = false
            }
            Link.send(msg("callSpeaker").put("on", actual))
        }, 400)
    }

    @Suppress("DEPRECATION")
    private fun onSpeaker(audio: AudioManager): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
            audio.communicationDevice?.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER
        else audio.isSpeakerphoneOn
}
