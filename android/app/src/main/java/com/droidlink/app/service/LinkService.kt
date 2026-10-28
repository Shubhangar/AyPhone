package com.droidlink.app.service

import android.Manifest
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.database.ContentObserver
import android.net.ConnectivityManager
import android.net.Network
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Telephony
import android.util.Log
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.droidlink.app.Notifications
import com.droidlink.app.feature.Battery
import com.droidlink.app.feature.ClipboardWatcher
import com.droidlink.app.feature.PhoneScreen
import com.droidlink.app.link.Discovery
import com.droidlink.app.link.Link
import com.droidlink.app.link.msg
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/** Foreground service that keeps the Mac connection alive in the background. */
class LinkService : Service() {
    companion object {
        fun start(ctx: Context) {
            try {
                ContextCompat.startForegroundService(ctx, Intent(ctx, LinkService::class.java))
            } catch (e: Exception) {
                // Android 12+ refuses foreground-service starts from the background; the app retries when opened.
                Log.w("AyPhone", "could not start service: $e")
            }
        }

        fun stop(ctx: Context) {
            ctx.stopService(Intent(ctx, LinkService::class.java))
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var discovery: Discovery
    private var smsObserverRegistered = false

    private val smsChanged = Runnable { Link.send(msg("smsChanged")) }
    private val smsObserver = object : ContentObserver(handler) {
        override fun onChange(selfChange: Boolean) {
            handler.removeCallbacks(smsChanged)
            handler.postDelayed(smsChanged, 1_000)
        }
    }

    /** A network coming up (Wi-Fi joined, phone woke) is a good moment to retry immediately. */
    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) = Link.poke()
    }

    override fun onCreate() {
        super.onCreate()
        ServiceCompat.startForeground(
            this, Notifications.STATUS_ID,
            Notifications.status(this, Link.state.value, Link.macName.value, Link.transport.value),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
        )
        ContextCompat.registerReceiver(
            this, Battery.receiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED), ContextCompat.RECEIVER_NOT_EXPORTED
        )
        // Screen and lock changes, for the Mac's ⌘V clipboard fetch (see PhoneScreen).
        registerReceiver(PhoneScreen.receiver, IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_USER_PRESENT)
        })
        getSystemService(ConnectivityManager::class.java).registerDefaultNetworkCallback(networkCallback)
        discovery = Discovery(this)
        scope.launch {
            combine(Link.state, Link.macName, Link.transport) { s, n, t -> Triple(s, n, t) }
                .collect { (state, name, transport) ->
                    Notifications.updateStatus(this@LinkService, Notifications.status(this@LinkService, state, name, transport))
                    // Look for the Mac on Wi-Fi while disconnected, and while on the Bluetooth fallback.
                    if (state == Link.State.Connecting || transport == "Bluetooth") discovery.start() else discovery.stop()
                }
        }
        Link.start()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Permissions may have been granted since the service started.
        registerSmsObserverIfAllowed()
        ClipboardWatcher.start(this)
        Link.start()
        return START_STICKY
    }

    private fun registerSmsObserverIfAllowed() {
        if (smsObserverRegistered) return
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_SMS) != PackageManager.PERMISSION_GRANTED) return
        try {
            contentResolver.registerContentObserver(Telephony.Sms.CONTENT_URI, true, smsObserver)
            smsObserverRegistered = true
        } catch (e: SecurityException) {
            Log.w("AyPhone", "SMS observer: $e")
        }
    }

    override fun onDestroy() {
        scope.cancel()
        unregisterReceiver(Battery.receiver)
        unregisterReceiver(PhoneScreen.receiver)
        getSystemService(ConnectivityManager::class.java).unregisterNetworkCallback(networkCallback)
        ClipboardWatcher.stop(this)
        if (smsObserverRegistered) contentResolver.unregisterContentObserver(smsObserver)
        handler.removeCallbacksAndMessages(null)
        discovery.stop()
        Link.stop()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
