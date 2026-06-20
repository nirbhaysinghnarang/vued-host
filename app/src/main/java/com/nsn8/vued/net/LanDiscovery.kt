package com.nsn8.vued.net

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.util.Log

/**
 * Advertises the on-device decrypt gateway over mDNS/Bonjour so other LAN
 * devices can find it without knowing the phone's IP. Service type is the
 * custom `_vued._tcp.`; browse for that to discover the host + port.
 *
 * Registration is best-effort: a failure is logged, not fatal — the server
 * still serves, callers just have to reach it by IP.
 */
class LanDiscovery(context: Context) {

    private val nsd = context.getSystemService(Context.NSD_SERVICE) as NsdManager
    private var listener: NsdManager.RegistrationListener? = null

    fun register(serviceName: String, port: Int) {
        unregister()
        val info = NsdServiceInfo().apply {
            this.serviceName = serviceName
            this.serviceType = SERVICE_TYPE
            this.port = port
        }
        val l = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(s: NsdServiceInfo) {
                Log.i(TAG, "registered ${s.serviceName} ($SERVICE_TYPE) on :$port")
            }
            override fun onRegistrationFailed(s: NsdServiceInfo, code: Int) {
                Log.e(TAG, "registration failed: $code")
            }
            override fun onServiceUnregistered(s: NsdServiceInfo) {
                Log.i(TAG, "unregistered ${s.serviceName}")
            }
            override fun onUnregistrationFailed(s: NsdServiceInfo, code: Int) {
                Log.e(TAG, "unregistration failed: $code")
            }
        }
        listener = l
        try {
            nsd.registerService(info, NsdManager.PROTOCOL_DNS_SD, l)
        } catch (e: Exception) {
            Log.e(TAG, "registerService threw: ${e.message}", e)
            listener = null
        }
    }

    fun unregister() {
        listener?.let {
            try {
                nsd.unregisterService(it)
            } catch (e: Exception) {
                Log.w(TAG, "unregisterService threw: ${e.message}")
            }
        }
        listener = null
    }

    companion object {
        const val SERVICE_TYPE = "_vued._tcp."
        private const val TAG = "VuedLanDiscovery"
    }
}
