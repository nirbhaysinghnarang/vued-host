package com.nsn8.vued.net

import android.content.Context
import android.util.Log
import fi.iki.elonen.NanoHTTPD
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.net.Inet4Address
import java.net.NetworkInterface
import java.security.KeyStore
import java.util.Collections
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLServerSocketFactory

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

    /** Fixed LAN port so the URL is stable across launches (mDNS still advertises it).
     *  If something else holds the port, start() throws and the gateway stays down. */
    private const val PORT = 8766

    private var server: LanDecryptServer? = null
    private var discovery: LanDiscovery? = null

    @Volatile
    var listeningPort: Int = -1
        private set

    @Volatile
    private var scheme: String = "http"

    /** The `.local` hostname JmDNS actually published (e.g. `vued-mic-1.local`),
     *  stable across DHCP IP changes. Set asynchronously once the service registers;
     *  until then registration falls back to the current site-local IPv4. */
    @Volatile
    private var mdnsHost: String? = null

    /** Base hostname we last asked JmDNS to advertise (without `.local`). Tracked so
     *  a room change re-advertises under the new mic-derived name. */
    @Volatile
    private var advertisedHost: String? = null

    val isRunning: Boolean get() = server?.isAlive == true

    @Synchronized
    fun start(context: Context) {
        if (server != null) return
        val app = context.applicationContext
        try {
            val s = LanDecryptServer(app, PORT)
            // Serve HTTPS if a keystore is bundled (res/raw/vued_lan_keystore),
            // so an HTTPS web page can reach it (mixed content). Falls back to
            // HTTP when absent. makeSecure() must precede start().
            val tls = sslFactory(app)
            if (tls != null) s.makeSecure(tls, null)
            s.start(NanoHTTPD.SOCKET_READ_TIMEOUT, false)
            server = s
            listeningPort = s.listeningPort
            scheme = if (tls != null) "https" else "http"
            discovery = LanDiscovery(app)
            Log.i(TAG, "decrypt gateway listening on $scheme:${s.listeningPort}")
            // mDNS advertisement + URL registration are driven by ensureAdvertised
            // (its resolve callback) and the auth-authenticated trigger in
            // MainActivity — no token is available yet here (VuedAuth.init runs later).
            ensureAdvertised(app)
        } catch (e: Throwable) {
            Log.e(TAG, "decrypt gateway failed to start: ${e.message}", e)
            stop()
        }
    }

    /**
     * Registers this tablet's gateway URL with the backend so the web dashboard
     * can discover it (the mDNS host id + IP change across reinstalls). device_id
     * is the assigned room's microphone id. Best-effort; re-call after the room
     * changes via [registerLanGatewayNow].
     */
    private fun registerLanGateway(app: Context) {
        if (!isRunning) return
        val orgId = RoomConfig.orgId(app)?.takeIf { it.isNotBlank() }
        val deviceId = RoomConfig.microphoneId(app)?.takeIf { it.isNotBlank() }
        // Prefer the stable mDNS .local hostname; fall back to the current IP only
        // until it resolves (or if resolution failed).
        val host = mdnsHost?.takeIf { isHostname(it) } ?: lanIpv4()
        if (orgId == null || deviceId == null || host == null) {
            Log.i(TAG, "LAN gateway not registered (org=$orgId mic=$deviceId host=$host)")
            return
        }
        val url = "$scheme://$host:$listeningPort"
        CoroutineScope(Dispatchers.IO).launch {
            try {
                OrgApi.registerLanGateway(orgId, deviceId, url)
                Log.i(TAG, "registered LAN gateway org=$orgId device=$deviceId url=$url")
            } catch (e: Exception) {
                Log.w(TAG, "LAN gateway register failed: ${e.message}")
            }
        }
    }

    /** Public trigger — call after the assigned room changes or auth becomes ready.
     *  Re-advertises mDNS if the mic-derived hostname changed, then re-registers the URL. */
    fun registerLanGatewayNow(context: Context) {
        val app = context.applicationContext
        ensureAdvertised(app)
        registerLanGateway(app)
    }

    /**
     * Advertises (or re-advertises) the gateway over mDNS under a host derived from
     * the assigned room's microphone id — `vued-<micId>.local`, unique per tablet so
     * two devices never collide on one name. Re-registers only when that name changes
     * (a no-op on repeat calls), so it's cheap to call on every room/auth event.
     * JmDNS conflict-resolution + read-back still cover the rare same-mic cross-org
     * case; the actual published name lands in [mdnsHost].
     */
    @Synchronized
    private fun ensureAdvertised(app: Context) {
        val d = discovery ?: return
        if (!isRunning) return
        val want = desiredHostName(app)
        if (want == advertisedHost && mdnsHost != null) return
        advertisedHost = want
        d.register(want, listeningPort) { host ->
            mdnsHost = host
            registerLanGateway(app)
        }
    }

    /** `vued-<sanitized micId>` (DNS-label safe), or `vued-recorder` before a room
     *  is assigned. The mic id is unique per tablet, so the name is too. */
    private fun desiredHostName(app: Context): String {
        val mic = RoomConfig.microphoneId(app)
            ?.trim()
            ?.lowercase()
            ?.replace(Regex("[^a-z0-9-]"), "-")
            ?.trim('-')
            ?.takeIf { it.isNotBlank() }
        return if (mic != null) "vued-$mic" else "vued-recorder"
    }

    /** True for a real hostname (e.g. `vued-mic-1.local`), false for an IPv4
     *  literal — so we only register a name when mDNS gave us one, else fall to IP. */
    private fun isHostname(s: String): Boolean =
        s.isNotBlank() && !s.matches(Regex("""^\d{1,3}(\.\d{1,3}){3}$"""))

    /** First site-local IPv4 (the LAN address the dashboard reaches us at). */
    private fun lanIpv4(): String? = try {
        Collections.list(NetworkInterface.getNetworkInterfaces())
            .asSequence()
            .filter { runCatching { it.isUp && !it.isLoopback }.getOrDefault(false) }
            .flatMap { Collections.list(it.inetAddresses).asSequence() }
            .filterIsInstance<Inet4Address>()
            .firstOrNull { it.isSiteLocalAddress }
            ?.hostAddress
    } catch (e: Exception) {
        null
    }

    /**
     * Builds an SSL factory from a bundled PKCS12 keystore at
     * res/raw/vued_lan_keystore (passphrase [TLS_PASSPHRASE]). Returns null when
     * the file is absent (→ run HTTP) or on any error. Generate the keystore with:
     *
     *   keytool -genkeypair -alias vued-lan -keyalg RSA -keysize 2048 -validity 3650 \
     *     -dname "CN=vued-recorder" -ext san=ip:0.0.0.0 \
     *     -keystore vued_lan_keystore -storetype PKCS12 -storepass vuedlan -keypass vuedlan
     *
     * then drop the file at app/src/main/res/raw/vued_lan_keystore (no extension).
     * Self-signed: accept the cert once in the browser (visit https://<ip>:<port>).
     */
    private fun sslFactory(app: Context): SSLServerSocketFactory? {
        val resId = app.resources.getIdentifier("vued_lan_keystore", "raw", app.packageName)
        if (resId == 0) return null
        return try {
            val ks = KeyStore.getInstance("PKCS12")
            app.resources.openRawResource(resId).use { ks.load(it, TLS_PASSPHRASE) }
            val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
            kmf.init(ks, TLS_PASSPHRASE)
            SSLContext.getInstance("TLS").apply { init(kmf.keyManagers, null, null) }.serverSocketFactory
        } catch (e: Throwable) {
            Log.e(TAG, "TLS setup failed; serving HTTP: ${e.message}", e)
            null
        }
    }

    private val TLS_PASSPHRASE = "vuedlan".toCharArray()

    @Synchronized
    fun stop() {
        discovery?.unregister()
        discovery = null
        server?.stop()
        server = null
        listeningPort = -1
        mdnsHost = null
        advertisedHost = null
    }
}
