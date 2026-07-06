package com.nsn8.vued

/**
 * Backend + Supabase configuration. The anon (publishable) key is intentionally
 * public — Row-Level Security, not key secrecy, protects user data. The
 * service-role key must NEVER appear in the app.
 */
object VuedConfig {
    const val SUPABASE_URL = "https://eubvwnuocitdcctwqjox.supabase.co"
    const val SUPABASE_ANON_KEY = "sb_publishable_dhS0lnu9IDvFPDi7TIzZwQ_hTgKl_eH"

    // Local development API running on this Mac over the LAN.
    const val API_BASE_URL = "http://10.0.0.32:8765"

    // Org-management API (orgs, rooms, members) — a separate service from the
    // recording backend above. Used to fetch the org's rooms so the tablet can
    // assign itself to one.
    const val ORG_API_BASE_URL = API_BASE_URL

    // Stream the 16-channel meeting source WAV to the server incrementally (as
    // each 30s segment closes) instead of only after the meeting stops. Kill
    // switch: set false to fall back to the stop-time whole-file upload.
    const val INCREMENTAL_SOURCE_WAV_UPLOAD = true

    // Source-audio upload codec: "wavpack" losslessly compresses each segment
    // (native libwavpack) before upload; "pcm" streams raw PCM. Kill switch:
    // set "pcm" to revert to the uncompressed path.
    const val SOURCE_WAV_CODEC = "wavpack"
}
