package com.droidlink.app.link

import android.Manifest
import android.bluetooth.BluetoothClass
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.os.SystemClock
import android.os.Build
import android.provider.Settings
import android.util.Log
import androidx.core.content.ContextCompat
import com.droidlink.app.feature.FileReceiver
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.Closeable
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.Socket
import java.util.UUID
import java.util.concurrent.Executors

fun msg(type: String): JSONObject = JSONObject().put("type", type)

/** One open link to the Mac, over a TCP socket (USB/Wi-Fi) or a Bluetooth RFCOMM socket. */
private class Conn(
    val input: InputStream,
    val output: OutputStream,
    val closer: Closeable,
    val host: String,
    val label: String,
)

/**
 * The connection to the Mac. Tries USB (127.0.0.1 via `adb reverse`), then Wi-Fi (Bonjour,
 * the Mac's beacon, remembered addresses, a network search), and finally Bluetooth for
 * networks that block devices from reaching each other. Reconnects with backoff.
 */
object Link {
    enum class State { Unpaired, Disconnected, Connecting, Connected }

    private const val TAG = "AyPhone"
    private const val MAX_FRAME = 16 * 1024 * 1024
    private const val USB_HOST = "127.0.0.1"
    private const val BLUETOOTH = "bluetooth"
    private const val QUICK_BLUETOOTH_RETRIES = 4
    /** The Mac's "AyPhone" Bluetooth service (must match BluetoothLinkServer.serviceUUID on the Mac). */
    private val BLUETOOTH_SERVICE: UUID = UUID.fromString("6f3c1a52-8b7e-4d2a-9c1e-5a7f0b3d2e91")

    val state = MutableStateFlow(State.Disconnected)
    val macName = MutableStateFlow<String?>(null)
    val transport = MutableStateFlow<String?>(null)

    /** Hosts that accepted a connection but failed the pairing handshake (another computer), with when. */
    private val rejected = HashMap<String, Long>()
    private var lastScan = 0L
    private var lastBluetoothTry = 0L
    /** Quick Bluetooth retries left after a link that dropped right away (see [run]). */
    private var bluetoothQuickRetries = QUICK_BLUETOOTH_RETRIES

    /** Latest address of our Mac found over Bonjour or its broadcast beacon; set by [Discovery]. */
    @Volatile var discoveredHost: String? = null

    lateinit var context: Context
        private set
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val writeLock = Any()
    private val writer = Executors.newSingleThreadExecutor()
    @Volatile private var conn: Closeable? = null
    @Volatile private var out: DataOutputStream? = null
    @Volatile private var crypto: LinkCrypto? = null
    private var loop: Job? = null
    /** Wakes the reconnect loop early (Mac re-announced itself, network came back). */
    private val wake = Channel<Unit>(Channel.CONFLATED)
    /** Set when USB accepted the socket but nothing answered (adb forwarding with the Mac app closed). */
    @Volatile private var skipUsbOnce = false

    val isConnected: Boolean get() = state.value == State.Connected

    fun init(ctx: Context) {
        context = ctx.applicationContext
        if (Pairing.load(context) == null) state.value = State.Unpaired
    }

    @Synchronized
    fun start() {
        if (loop?.isActive == true) return
        loop = scope.launch { run() }
    }

    @Synchronized
    fun stop() {
        loop?.cancel()
        loop = null
        closeSocket()
        macName.value = null
        state.value = if (Pairing.load(context) == null) State.Unpaired else State.Disconnected
    }

    /** Retry right away instead of waiting out the backoff. */
    fun poke() {
        if (state.value == State.Connecting) wake.trySend(Unit)
    }

    fun restart() {
        stop()
        start()
    }

    /** Queues a message. Safe from any thread; dropped when not connected. */
    fun send(json: JSONObject) {
        if (out == null) return
        writer.execute {
            try {
                sendNow(json)
            } catch (e: IOException) {
                Log.w(TAG, "send failed: $e")
                closeSocket()
            }
        }
    }

    /** Writes a message immediately, blocking. Use from background threads only. */
    @Throws(IOException::class)
    fun sendNow(json: JSONObject) {
        synchronized(writeLock) {
            val o = out ?: throw IOException("not connected")
            val box = crypto!!.seal(json.toString().toByteArray(Charsets.UTF_8))
            o.writeInt(box.size)
            o.write(box)
            o.flush()
        }
    }

    private suspend fun run() {
        var backoff = 1_000L
        while (currentCoroutineContext().isActive) {
            val pairing = Pairing.load(context)
            if (pairing == null) {
                state.value = State.Unpaired
                return
            }
            state.value = State.Connecting
            val connected = connect(pairing)
            if (connected != null) {
                val host = connected.host
                val openedAt = SystemClock.elapsedRealtime()
                var authenticated = false
                try {
                    session(connected, pairing) {
                        authenticated = true
                        if (host != USB_HOST && host != BLUETOOTH) Pairing.rememberHost(context, host)
                    }
                } catch (e: Exception) {
                    Log.i(TAG, "session ended: $e")
                }
                closeSocket()
                FileReceiver.abortAll(context)
                macName.value = null
                transport.value = null
                if (currentCoroutineContext().isActive) state.value = State.Connecting
                if (authenticated) backoff = 1_000L
                if (host == USB_HOST && !authenticated) skipUsbOnce = true
                if (host != USB_HOST && host != BLUETOOTH && !authenticated) rejected[host] = SystemClock.elapsedRealtime()
                if (host == BLUETOOTH) {
                    // While the Mac is still connecting its hands-free (calls) link to this phone,
                    // macOS hands our new channel to that instead and it drops. That passes in a few
                    // seconds, so retry soon rather than waiting out the usual minute.
                    if (SystemClock.elapsedRealtime() - openedAt < 10_000 && bluetoothQuickRetries > 0) {
                        bluetoothQuickRetries--
                        lastBluetoothTry = SystemClock.elapsedRealtime() - 60_000 + 8_000
                    } else if (authenticated) {
                        bluetoothQuickRetries = QUICK_BLUETOOTH_RETRIES
                    }
                }
            }
            // Sleep until the backoff expires or something suggests the Mac is reachable again.
            val poked = withTimeoutOrNull(backoff) { wake.receive() } != null
            backoff = if (poked) 1_000L else (backoff * 2).coerceAtMost(10_000L)
        }
    }

    /**
     * Finds the Mac, most likely first: USB, then where Bonjour or the Mac's broadcast beacon
     * says it is, then every address it was reached at before, and finally (when none of those
     * answer, e.g. on a new network that blocks discovery) a quick search of this Wi-Fi, and
     * last of all Bluetooth, for networks that keep devices from reaching each other.
     * Whatever answers still has to pass the encrypted pairing handshake in [session].
     */
    private suspend fun connect(p: PairingInfo): Conn? {
        val now = SystemClock.elapsedRealtime()
        rejected.entries.removeAll { now - it.value > 10 * 60_000 }
        val candidates = buildList {
            if (!skipUsbOnce) add(USB_HOST)
            discoveredHost?.let(::add)
            addAll(p.hosts)
        }.distinct().filter { it !in rejected }
        skipUsbOnce = false
        for (host in candidates) {
            open(host, p.port, if (host == USB_HOST) 600 else 2_000)?.let { return it }
        }
        if (now - lastScan > 30_000) {
            lastScan = now
            for (host in scanSubnet(p.port)) {
                if (host in candidates || host in rejected) continue
                Log.i(TAG, "found a candidate Mac at $host by searching the network")
                open(host, p.port, 2_000)?.let { return it }
            }
        }
        // Bluetooth attempts make the phone and Mac connect and disconnect at the radio level
        // (visible in Bluetooth settings), so try at most once a minute.
        if (now - lastBluetoothTry < 60_000) return null
        lastBluetoothTry = now
        return openBluetooth(p)
    }

    private fun open(host: String, port: Int, timeoutMs: Int): Conn? = try {
        val s = Socket().apply {
            connect(InetSocketAddress(host, port), timeoutMs)
            tcpNoDelay = true
            keepAlive = true
            soTimeout = 45_000
        }
        val label = if (host == USB_HOST) "USB" else "Wi-Fi"
        transport.value = label
        Conn(s.getInputStream(), s.getOutputStream(), s, host, label)
    } catch (e: IOException) {
        null
    }

    /** RFCOMM to the paired Mac's "AyPhone" Bluetooth service (paired computers, the Mac's name first). */
    private fun openBluetooth(p: PairingInfo): Conn? {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
            return null
        }
        val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter ?: return null
        if (!adapter.isEnabled) return null
        val computers = try {
            adapter.bondedDevices
                .filter { it.bluetoothClass?.majorDeviceClass == BluetoothClass.Device.Major.COMPUTER }
                .sortedByDescending { (it.name ?: "") == p.macName }
        } catch (e: SecurityException) {
            return null
        }
        for (device in computers) {
            try {
                // (No cancelDiscovery(): AyPhone never scans, and that call needs the scan permission.)
                val s = device.createRfcommSocketToServiceRecord(BLUETOOTH_SERVICE)
                s.connect()
                Log.i(TAG, "connected to ${device.name} over Bluetooth")
                transport.value = "Bluetooth"
                return Conn(s.inputStream, s.outputStream, s, BLUETOOTH, "Bluetooth")
            } catch (e: Exception) {
                Log.i(TAG, "no AyPhone Bluetooth service on ${device.name}: $e")
            }
        }
        return null
    }

    /** While on Bluetooth: is the Mac reachable over USB or Wi-Fi again? */
    private fun fasterLinkAvailable(p: PairingInfo): Boolean {
        val candidates = buildList {
            add(USB_HOST)
            discoveredHost?.let(::add)
            addAll(p.hosts)
        }.distinct().filter { it !in rejected }
        return candidates.any { host ->
            try {
                Socket().use { it.connect(InetSocketAddress(host, p.port), if (host == USB_HOST) 400 else 1_500) }
                true
            } catch (_: IOException) {
                false
            }
        }
    }

    /** Every address on the phone's /24 that has AyPhone's port open (about 2 seconds, in parallel). */
    private suspend fun scanSubnet(port: Int): List<String> = coroutineScope {
        val cm = context.getSystemService(ConnectivityManager::class.java)
        val own = cm.getLinkProperties(cm.activeNetwork)?.linkAddresses
            ?.map { it.address }?.firstOrNull { it is Inet4Address && !it.isLoopbackAddress }
            ?: return@coroutineScope emptyList()
        val b = own.address
        val prefix = "${b[0].toInt() and 0xFF}.${b[1].toInt() and 0xFF}.${b[2].toInt() and 0xFF}."
        val self = b[3].toInt() and 0xFF
        (1..254).filter { it != self }.map { i ->
            async(Dispatchers.IO) {
                val ip = prefix + i
                try {
                    Socket().use { it.connect(InetSocketAddress(ip, port), 400) }
                    ip
                } catch (_: IOException) {
                    null
                }
            }
        }.awaitAll().filterNotNull()
    }

    private fun session(link: Conn, p: PairingInfo, onAuthenticated: () -> Unit) {
        val c = LinkCrypto(p.key)
        val input = DataInputStream(BufferedInputStream(link.input, 64 * 1024))
        synchronized(writeLock) {
            conn = link.closer
            crypto = c
            out = DataOutputStream(BufferedOutputStream(link.output, 64 * 1024))
        }
        sendNow(
            msg("hello")
                .put("name", deviceName())
                .put("model", "${Build.MANUFACTURER} ${Build.MODEL}")
                .put("id", Pairing.deviceId(context))
        )
        val heartbeat = scope.launch {
            while (isActive) {
                delay(15_000)
                send(msg("ping"))
            }
        }
        // Bluetooth is the fallback: go back to USB or Wi-Fi as soon as either works again.
        val upgrade = if (link.label == "Bluetooth") scope.launch {
            while (isActive) {
                delay(45_000)
                if (fasterLinkAvailable(p)) {
                    Log.i(TAG, "USB or Wi-Fi reachable again; leaving Bluetooth")
                    closeSocket()
                    break
                }
            }
        } else null
        try {
            while (true) {
                val len = input.readInt()
                if (len <= 28 || len > MAX_FRAME) throw IOException("bad frame length $len")
                val box = ByteArray(len)
                input.readFully(box)
                val json = JSONObject(String(c.open(box), Charsets.UTF_8))
                if (json.optString("type") == "helloOk") {
                    macName.value = json.optString("name", p.macName)
                    state.value = State.Connected
                    onAuthenticated()
                    Router.onConnected()
                    continue
                }
                try {
                    Router.handle(json)
                } catch (e: Exception) {
                    Log.w(TAG, "handler for ${json.optString("type")} failed", e)
                }
            }
        } finally {
            heartbeat.cancel()
            upgrade?.cancel()
        }
    }

    private fun closeSocket() {
        try { conn?.close() } catch (_: IOException) {}
        synchronized(writeLock) {
            out = null
            crypto = null
            conn = null
        }
    }

    private fun deviceName(): String =
        Settings.Global.getString(context.contentResolver, Settings.Global.DEVICE_NAME) ?: Build.MODEL
}
