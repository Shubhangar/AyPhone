package com.droidlink.app.link

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.util.Log
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetSocketAddress
import kotlin.concurrent.thread

/**
 * Finds the paired Mac's current address while disconnected: over Bonjour (`_droidlink._tcp`),
 * and through the Mac's UDP broadcast beacon (port 47801), which gets through on many networks
 * that block Bonjour. Either way the Mac still has to pass the pairing handshake.
 */
class Discovery(private val ctx: Context) {
    companion object {
        const val BEACON_PORT = 47801
    }

    private val nsd = ctx.getSystemService(NsdManager::class.java)
    private var listener: NsdManager.DiscoveryListener? = null
    @Volatile private var beaconSocket: DatagramSocket? = null
    /** Without this, Wi-Fi drivers may drop broadcast packets to save power. */
    private val multicastLock = ctx.applicationContext.getSystemService(WifiManager::class.java)
        .createMulticastLock("AyPhone discovery").apply { setReferenceCounted(false) }

    fun start() {
        startBeaconListener()
        if (listener != null) return
        val l = object : NsdManager.DiscoveryListener {
            override fun onServiceFound(info: NsdServiceInfo) = resolve(info)
            override fun onServiceLost(info: NsdServiceInfo) {}
            override fun onDiscoveryStarted(serviceType: String) {}
            override fun onDiscoveryStopped(serviceType: String) {}
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) { listener = null }
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {}
        }
        listener = l
        try {
            nsd.discoverServices("_droidlink._tcp", NsdManager.PROTOCOL_DNS_SD, l)
        } catch (e: Exception) {
            listener = null
        }
    }

    fun stop() {
        listener?.let { try { nsd.stopServiceDiscovery(it) } catch (_: Exception) {} }
        listener = null
        beaconSocket?.close()
        beaconSocket = null
        if (multicastLock.isHeld) multicastLock.release()
    }

    private fun startBeaconListener() {
        if (beaconSocket != null) return
        val socket = try {
            DatagramSocket(null).apply {
                reuseAddress = true
                broadcast = true
                bind(InetSocketAddress(BEACON_PORT))
            }
        } catch (e: Exception) {
            Log.w("AyPhone", "beacon listener unavailable: $e")
            return
        }
        beaconSocket = socket
        multicastLock.acquire()
        thread(name = "beacon-listener", isDaemon = true) {
            val buf = ByteArray(512)
            while (beaconSocket === socket) {
                try {
                    val packet = DatagramPacket(buf, buf.size)
                    socket.receive(packet)
                    val text = String(packet.data, 0, packet.length, Charsets.UTF_8)
                    // "AYPHONE1 <port> <mac name>"
                    if (!text.startsWith("AYPHONE1 ")) continue
                    val host = (packet.address as? Inet4Address)?.hostAddress ?: continue
                    if (Link.discoveredHost != host) {
                        Link.discoveredHost = host
                        Link.poke()
                    }
                } catch (_: Exception) {
                    // Socket closed by stop(), or a bad packet.
                }
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun resolve(info: NsdServiceInfo) {
        // Only follow the Mac we paired with; another Mac on the network has a different key.
        val pairedName = Pairing.load(ctx)?.macName ?: return
        if (!info.serviceName.startsWith(pairedName)) return
        nsd.resolveService(info, object : NsdManager.ResolveListener {
            override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {}
            override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                val host = serviceInfo.host
                if (host is Inet4Address) {
                    Link.discoveredHost = host.hostAddress
                    Link.poke()
                }
            }
        })
    }
}
