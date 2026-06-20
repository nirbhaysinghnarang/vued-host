package com.nsn8.vued.net

import android.content.Context
import android.util.Log
import fi.iki.elonen.NanoHTTPD

/**
 * Process-level owner of the LAN decrypt gateway. Brought up on app launch
 * (from [com.nsn8.vued.App.onCreate]) and kept alive for the life of the
 * process — independent of recording. Idempotent: repeated [start] calls no-op
 * while it's already running.
 *
 * Decryption still requires the device to be provisioned; until then the server
 * runs but answers reads with 503 (handled in [LanDecryptServer]).
 */
object DecryptGateway {

    private const val TAG = "VuedDecryptGateway"

    private var server: LanDecryptServer? = null
    private var discovery: LanDiscovery? = null

    @Volatile
    var listeningPort: Int = -1
        private set

    val isRunning: Boolean get() = server?.isAlive == true

    @Synchronized
    fun start(context: Context) {
        if (server != null) return
        val app = context.applicationContext
        try {
            val s = LanDecryptServer(app).apply { start(NanoHTTPD.SOCKET_READ_TIMEOUT, false) }
            server = s
            listeningPort = s.listeningPort
            discovery = LanDiscovery(app).also { it.register("vued-recorder", s.listeningPort) }
            Log.i(TAG, "decrypt gateway listening on :${s.listeningPort}")
        } catch (e: Throwable) {
            Log.e(TAG, "decrypt gateway failed to start: ${e.message}", e)
            stop()
        }
    }

    @Synchronized
    fun stop() {
        discovery?.unregister()
        discovery = null
        server?.stop()
        server = null
        listeningPort = -1
    }
}
