package com.droidlink.app.feature

import android.Manifest
import android.bluetooth.BluetoothClass
import android.bluetooth.BluetoothManager
import android.companion.AssociationInfo
import android.companion.AssociationRequest
import android.companion.BluetoothDeviceFilter
import android.companion.CompanionDeviceManager
import android.content.Context
import android.content.IntentSender
import android.content.pm.PackageManager
import android.os.Build
import android.telecom.Call
import android.telecom.CallAudioState
import android.telecom.InCallService
import android.telecom.VideoProfile
import android.util.Log
import androidx.core.content.ContextCompat
import com.droidlink.app.link.Link
import com.droidlink.app.link.Pairing
import com.droidlink.app.link.msg

/**
 * Call controls from the Mac (speaker, mute, hang up) for phone and WhatsApp-style calls.
 * Android only lets the calling app or a companion device's app steer a call, so AyPhone
 * registers the paired Mac as its companion device (Android's "watch" profile is the only one
 * ordinary apps may use). Once approved, Android binds [CallCompanionService] during calls.
 */
object CallCompanion {
    fun approved(ctx: Context): Boolean {
        val cdm = ctx.getSystemService(CompanionDeviceManager::class.java) ?: return false
        return if (Build.VERSION.SDK_INT >= 33) cdm.myAssociations.isNotEmpty()
        else @Suppress("DEPRECATION") cdm.associations.isNotEmpty()
    }

    /** Asks Android to approve the Mac as AyPhone's companion. [launch] shows Android's dialog. */
    fun request(ctx: Context, launch: (IntentSender) -> Unit, done: (String?) -> Unit) {
        val cdm = ctx.getSystemService(CompanionDeviceManager::class.java)
            ?: return done("This phone doesn't support companion devices")
        val mac = pairedMac(ctx) ?: return done("Pair this phone with your Mac in Bluetooth settings first")
        val request = AssociationRequest.Builder()
            .addDeviceFilter(BluetoothDeviceFilter.Builder().setAddress(mac).build())
            .setSingleDevice(true)
            .setDeviceProfile(AssociationRequest.DEVICE_PROFILE_WATCH)
            .build()
        if (Build.VERSION.SDK_INT >= 33) {
            cdm.associate(request, ctx.mainExecutor, object : CompanionDeviceManager.Callback() {
                override fun onAssociationPending(intentSender: IntentSender) = launch(intentSender)
                override fun onAssociationCreated(associationInfo: AssociationInfo) = done(null)
                override fun onFailure(error: CharSequence?) = done(error?.toString() ?: "Not approved")
            })
        } else {
            @Suppress("DEPRECATION")
            cdm.associate(request, object : CompanionDeviceManager.Callback() {
                @Deprecated("Deprecated in Java")
                override fun onDeviceFound(intentSender: IntentSender) = launch(intentSender)
                override fun onFailure(error: CharSequence?) = done(error?.toString() ?: "Not approved")
            }, null)
        }
    }

    /** The Bluetooth address of the paired Mac (computers paired with the phone, the Mac's name first). */
    private fun pairedMac(ctx: Context): String? {
        if (Build.VERSION.SDK_INT >= 31 &&
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) return null
        val adapter = ctx.getSystemService(BluetoothManager::class.java)?.adapter ?: return null
        val macName = Pairing.load(ctx)?.macName
        return try {
            adapter.bondedDevices
                .filter { it.bluetoothClass?.majorDeviceClass == BluetoothClass.Device.Major.COMPUTER }
                .sortedByDescending { (it.name ?: "") == macName }
                .firstOrNull()?.address
        } catch (e: SecurityException) {
            null
        }
    }
}

/** Bound by Android during calls once the Mac is approved as a companion (see [CallCompanion]). */
@Suppress("DEPRECATION") // callAudioState/setAudioRoute: the newer endpoint API needs Android 14
class CallCompanionService : InCallService() {
    companion object {
        @Volatile var instance: CallCompanionService? = null
            private set
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
    }

    override fun onDestroy() {
        instance = null
        super.onDestroy()
    }

    override fun onCallAudioStateChanged(audioState: CallAudioState) {
        Link.send(msg("callSpeaker").put("on", audioState.route == CallAudioState.ROUTE_SPEAKER))
    }

    override fun onCallRemoved(call: Call) {
        if (calls.isEmpty()) Link.send(msg("callSpeaker").put("on", false))
    }

    /** Runs a Mac call button; false when there's no call to act on. */
    fun perform(action: String): Boolean {
        val ringing = calls.firstOrNull { it.details.state == Call.STATE_RINGING }
        val current = calls.firstOrNull { it.details.state == Call.STATE_ACTIVE } ?: ringing ?: calls.firstOrNull()
            ?: return false
        Log.i("AyPhone", "call companion: $action")
        when (action) {
            "answer" -> (ringing ?: return false).answer(VideoProfile.STATE_AUDIO_ONLY)
            "decline" -> (ringing ?: return false).reject(false, null)
            "end" -> current.disconnect()
            "mute" -> setMuted(!(callAudioState?.isMuted ?: false))
            "speaker" -> setAudioRoute(
                if (callAudioState?.route == CallAudioState.ROUTE_SPEAKER) CallAudioState.ROUTE_WIRED_OR_EARPIECE
                else CallAudioState.ROUTE_SPEAKER
            )
            else -> return false
        }
        return true
    }
}
