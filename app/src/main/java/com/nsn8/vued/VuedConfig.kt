package com.nsn8.vued

/**
 * Backend + Supabase configuration. The anon (publishable) key is intentionally
 * public — Row-Level Security, not key secrecy, protects user data. The
 * service-role key must NEVER appear in the app.
 */
object VuedConfig {
    const val SUPABASE_URL = "https://eubvwnuocitdcctwqjox.supabase.co"
    const val SUPABASE_ANON_KEY = "sb_publishable_dhS0lnu9IDvFPDi7TIzZwQ_hTgKl_eH"

    // Stateless STT / API backend for the fresh office-dev stack.
    const val API_BASE_URL = "https://vued-office-api-dev.onrender.com"

    // Org-management API (orgs, rooms, members) — a separate service from the
    // recording backend above. Used to fetch the org's rooms so the tablet can
    // assign itself to one.
    const val ORG_API_BASE_URL = API_BASE_URL
}
