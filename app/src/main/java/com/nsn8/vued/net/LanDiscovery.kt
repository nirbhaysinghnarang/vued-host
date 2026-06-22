package com.nsn8.vued.net

import android.content.Context
import android.net.wifi.WifiManager
import android.util.Log
import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.Collections
import java.util.Timer
import java.util.TimerTask
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
 * Keeps the mDNS path warm so clients don't pay a slow cold lookup:
 *  - A **multicast lock** (required) so Android delivers inbound mDNS queries.
 *  - A **high-perf Wi-Fi lock** so the radio doesn't drop into power-save and
 *    delay receipt of those queries (the main cause of a cold `.local` lookup
 *    timing out even though we'd answer in ~200ms once it arrives).
 *  - A **periodic re-announce** ([ANNOUNCE_INTERVAL_MS]) so resolvers that
 *    passively cache overheard announcements stay seeded between active queries.
 *
 * Best-effort: a failure is logged and the callback gets null (the gateway then
 * falls back to its IP).
 */
class LanDiscovery(context: Context) {

    private val appContext = context.applicationContext
    private var jmdns: JmDNS? = null
    private var multicastLock: WifiManager.MulticastLock? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private var announceTimer: Timer? = null

    @Volatile
    private var advertisedHost: String? = null

    @Volatile
    private var advertisedPort: Int = 0

    /**
     * Publishes `_vued._tcp` on [port] under host [hostName] (→ `<hostName>.local`).
     *
     * @param onHostResolved invoked off the main thread with the actual published
     *   host (e.g. `vued-recorder.local`, or `vued-recorder-2.local` if the name
     *   was already taken), or null if registration failed.
     */
    fun register(hostName: String, port: Int, onHostResolved: ((String?) -> Unit)? = null) {
        unregister()
        advertisedHost = hostName
        advertisedPort = port
        // JmDNS.create + registerService do blocking network IO — keep off main.
        thread(name = "vued-mdns") {
            try {
                val wifi = appContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
                multicastLock = wifi.createMulticastLock("vued-mdns").apply {
                    setReferenceCounted(true)
                    acquire()
                }
                // Keep Wi-Fi out of power-save so inbound mDNS queries are received
                // promptly (otherwise the first cold lookup waits for a wake interval).
                @Suppress("DEPRECATION")
                wifiLock = wifi.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "vued-gateway").apply {
                    setReferenceCounted(false)
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
                startReannounce()
            } catch (e: Throwable) {
                Log.e(TAG, "jmdns register failed: ${e.message}", e)
                onHostResolved?.invoke(null)
            }
        }
    }

    /** Periodically re-announce the service so neighbor mDNS caches stay seeded. */
    private fun startReannounce() {
        announceTimer?.cancel()
        announceTimer = Timer("vued-mdns-announce", true).also { timer ->
            timer.scheduleAtFixedRate(object : TimerTask() {
                override fun run() = reannounce()
            }, ANNOUNCE_INTERVAL_MS, ANNOUNCE_INTERVAL_MS)
        }
    }

    /** Re-emit the service announcement (JmDNS has no public re-announce, so we
     *  unregister + re-register the same name). Runs on the timer thread. */
    private fun reannounce() {
        val jm = jmdns ?: return
        val host = advertisedHost ?: return
        try {
            jm.unregisterAllServices()
            jm.registerService(ServiceInfo.create(SERVICE_TYPE, host, advertisedPort, ""))
            Log.i(TAG, "re-announced $SERVICE_TYPE host=$host")
        } catch (e: Exception) {
            Log.w(TAG, "re-announce failed: ${e.message}")
        }
    }

    fun unregister() {
        announceTimer?.cancel()
        announceTimer = null
        val jm = jmdns
        jmdns = null
        val mLock = multicastLock
        multicastLock = null
        val wLock = wifiLock
        wifiLock = null
        if (jm == null && mLock == null && wLock == null) return
        thread(name = "vued-mdns-stop") {
            try {
                jm?.unregisterAllServices()
                jm?.close()
            } catch (e: Exception) {
                Log.w(TAG, "jmdns close threw: ${e.message}")
            }
            try {
                mLock?.release()
            } catch (e: Exception) {
                Log.w(TAG, "multicast lock release threw: ${e.message}")
            }
            try {
                if (wLock?.isHeld == true) wLock.release()
            } catch (e: Exception) {
                Log.w(TAG, "wifi lock release threw: ${e.message}")
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
        /** Re-announce cadence — keeps resolver caches warm between active queries. */
        private const val ANNOUNCE_INTERVAL_MS = 60_000L
    }
}
