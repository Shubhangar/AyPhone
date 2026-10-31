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
        Link.send(msg("callMute").put("on", audioState.isMuted))
    }

    /** Catches a Mac that connects mid-call up on the calls, speaker and mute. */
    fun sendCurrent() {
        lastReport = null
        reportCalls()
        callAudioState?.let(::onCallAudioStateChanged)
    }

    /** The last `call` message sent, so repeated details updates don't resend it. */
    private var lastReport: String? = null

    private val callUpdates = object : Call.Callback() {
        override fun onStateChanged(call: Call, state: Int) = reportCalls()
        override fun onDetailsChanged(call: Call, details: Call.Details) = reportCalls()
    }

    override fun onCallAdded(call: Call) {
        call.registerCallback(callUpdates)
        reportCalls()
    }

    override fun onCallRemoved(call: Call) {
        call.unregisterCallback(callUpdates)
        reportCalls()
        if (calls.isEmpty()) {
            Link.send(msg("callSpeaker").put("on", false))
            Link.send(msg("callMute").put("on", false))
        }
    }

    /**
     * Sends the phone calls (WhatsApp-style calls come through their notifications) as one `call`
     * message. Android's own call-state broadcast can't tell a second caller from the person
     * you're talking to, so while bound this replaces it ([Calls.onPhoneState]): a call ringing
     * during another one is sent as `waiting`.
     */
    private fun reportCalls() {
        val phoneCalls = calls.filter {
            !it.details.hasProperty(Call.Details.PROPERTY_SELF_MANAGED) &&
                it.state != Call.STATE_DISCONNECTED && it.state != Call.STATE_DISCONNECTING
        }
        val ringing = phoneCalls.firstOrNull { it.state == Call.STATE_RINGING }
        val ongoing = phoneCalls.filter { it !== ringing }
        val main = ongoing.firstOrNull { it.state == Call.STATE_ACTIVE } ?: ongoing.firstOrNull() ?: ringing
        val m = msg("call").put("telecom", true).put("state", when {
            main == null -> "idle"
            main === ringing -> "ringing"
            else -> "offhook"
        })
        main?.let { describe(it) }?.let { (number, name) -> m.put("number", number).put("name", name) }
        if (ringing != null && main !== ringing) {
            describe(ringing)?.let { (number, name) -> m.put("waitingNumber", number).put("waitingName", name) }
                ?: m.put("waitingName", "Unknown caller")
        }
        val report = m.toString()
        if (report == lastReport) return
        lastReport = report
        Link.send(m)
    }

    /** A call's number and contact name (either may be null; null when neither is known). */
    private fun describe(call: Call): Pair<String?, String?>? {
        val number = call.details.handle?.schemeSpecificPart?.takeIf { it.isNotEmpty() }
        val name = number?.let { Sms.contactName(this, it) }
            ?: (if (Build.VERSION.SDK_INT >= 30) call.details.contactDisplayName else null)
            ?: call.details.callerDisplayName
        return if (number == null && name.isNullOrEmpty()) null else number to name?.takeIf { it.isNotEmpty() }
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
