package com.nsn8.vued

/**
 * Backend + Supabase configuration. The anon (publishable) key is intentionally
 * public — Row-Level Security, not key secrecy, protects user data. The
 * service-role key must NEVER appear in the app.
 */
object VuedConfig {

    enum class Mode {
        DEV,
        PROD,
        STAGING,
    }

    val MODE = Mode.STAGING

    private const val SUPABASE_URL_PROD = "https://fmzwemrvhiyyotswkplb.supabase.co"
    private const val SUPABASE_URL_DEV = "https://eubvwnuocitdcctwqjox.supabase.co"
    private const val SUPABASE_ANON_KEY_PROD = "sb_publishable_wEIaal3mcF9ZcbLXT9NF3w_heVIvHlX"
    private const val SUPABASE_ANON_KEY_DEV = "sb_publishable_dhS0lnu9IDvFPDi7TIzZwQ_hTgKl_eH"
    private const val SUPABASE_URL_STAGING = "https://eubvwnuocitdcctwqjox.supabase.co"
    private const val SUPABASE_ANON_KEY_STAGING = "sb_publishable_dhS0lnu9IDvFPDi7TIzZwQ_hTgKl_eH"



    val SUPABASE_URL = when (MODE) {
        Mode.DEV -> SUPABASE_URL_DEV
        Mode.PROD -> SUPABASE_URL_PROD
        Mode.STAGING -> SUPABASE_URL_STAGING
    }
    val SUPABASE_ANON_KEY = when (MODE) {
        Mode.DEV -> SUPABASE_ANON_KEY_DEV
        Mode.PROD -> SUPABASE_ANON_KEY_PROD
        Mode.STAGING -> SUPABASE_ANON_KEY_STAGING
    }

    // Stateless STT / API backend for the fresh office-dev stack.
    private const val API_BASE_URL_DEV = "https://vued-office-gss-api.onrender.com"
    private const val API_BASE_URL_PROD = "https://vued-office-api-dev.onrender.com"
    private const val API_BASE_URL_STAGING = "https://vued-office-staging-api.onrender.com"


    val API_BASE_URL = when (MODE) {
        Mode.DEV -> API_BASE_URL_DEV
        Mode.PROD -> API_BASE_URL_PROD
        Mode.STAGING -> API_BASE_URL_STAGING
    }

    // Org-management API (orgs, rooms, members) — a separate service from the
    // recording backend above. Used to fetch the org's rooms so the tablet can
    // assign itself to one.
    val ORG_API_BASE_URL = API_BASE_URL

    // Keep recording available when no UMA array is attached. The recording
    // service still prefers UMA-8/16 and automatically switches back when one
    // becomes available.
    const val ALLOW_BUILT_IN_MIC_FALLBACK = true

    const val AMPLITUDE_API_KEY = "a238d3271139545c5a533d67df8d8351"

    // Mirrors the headless release flow: public Supabase Storage manifests under
    // downloads/android-host/<channel>/manifest.json with immutable APKs by version.
    val UPDATE_RELEASE_BASE_URL = "$SUPABASE_URL/storage/v1/object/public/downloads/android-host"
    const val UPDATE_CHANNEL = "latest"
}
