package com.nsn8.vued.net

import android.content.Context
import android.net.wifi.WifiManager
import android.util.Log
import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.Collections
import javax.jmdns.JmDNS
import javax.jmdns.ServiceInfo
import kotlin.concurrent.thread

/**
 * Advertises the on-device decrypt gateway over mDNS/Bonjour so other LAN
 * devices (the web dashboard's OS resolver) can reach it by a stable name
 * instead of the DHCP-ephemeral IP.
 *
 * Uses JmDNS rather than Android's [android.net.nsd.NsdManager] for one reason:
 * NsdManager won't tell us the device's mDNS hostname (resolve hands back an
 * InetAddress whose name is just the IP), whereas JmDNS lets us *set* the host
 * name we publish and *read it back* (with conflict-resolution suffix) — so the
 * gateway can register a real `<host>.local` URL.
 *
 * Requires a Wi-Fi multicast lock; without it Android drops the inbound mDNS
 * queries and nothing on the LAN can resolve us. Best-effort: a failure is
 * logged and the callback gets null (the gateway then falls back to its IP).
 */
class LanDiscovery(context: Context) {

    private val appContext = context.applicationContext
    private var jmdns: JmDNS? = null
    private var multicastLock: WifiManager.MulticastLock? = null

    /**
     * Publishes `_vued._tcp` on [port] under host [hostName] (→ `<hostName>.local`).
     *
     * @param onHostResolved invoked off the main thread with the actual published
     *   host (e.g. `vued-recorder.local`, or `vued-recorder-2.local` if the name
     *   was already taken), or null if registration failed.
     */
    fun register(hostName: String, port: Int, onHostResolved: ((String?) -> Unit)? = null) {
        unregister()
        // JmDNS.create + registerService do blocking network IO — keep off main.
        thread(name = "vued-mdns") {
            try {
                val wifi = appContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
                multicastLock = wifi.createMulticastLock("vued-mdns").apply {
                    setReferenceCounted(true)
                    acquire()
                }
                val addr = lanInet4()
                val jm = JmDNS.create(addr, hostName)
                jmdns = jm
                val info = ServiceInfo.create(SERVICE_TYPE, hostName, port, "")
                jm.registerService(info)
                val host = info.server?.trim()?.removeSuffix(".")?.takeIf { it.isNotBlank() }
                Log.i(TAG, "registered $SERVICE_TYPE host=$host :$port (bind=${addr?.hostAddress})")
                onHostResolved?.invoke(host)
            } catch (e: Throwable) {
                Log.e(TAG, "jmdns register failed: ${e.message}", e)
                onHostResolved?.invoke(null)
            }
        }
    }

    fun unregister() {
        val jm = jmdns
        jmdns = null
        val lock = multicastLock
        multicastLock = null
        if (jm == null && lock == null) return
        thread(name = "vued-mdns-stop") {
            try {
                jm?.unregisterAllServices()
                jm?.close()
            } catch (e: Exception) {
                Log.w(TAG, "jmdns close threw: ${e.message}")
            }
            try {
                lock?.release()
            } catch (e: Exception) {
                Log.w(TAG, "multicast lock release threw: ${e.message}")
            }
        }
    }

    /** Site-local IPv4 to bind JmDNS to (the LAN interface the dashboard reaches). */
    private fun lanInet4(): Inet4Address? = try {
        Collections.list(NetworkInterface.getNetworkInterfaces())
            .asSequence()
            .filter { runCatching { it.isUp && !it.isLoopback }.getOrDefault(false) }
            .flatMap { Collections.list(it.inetAddresses).asSequence() }
            .filterIsInstance<Inet4Address>()
            .firstOrNull { it.isSiteLocalAddress }
    } catch (e: Exception) {
        null
    }

    companion object {
        const val SERVICE_TYPE = "_vued._tcp.local."
        private const val TAG = "VuedLanDiscovery"
    }
}
