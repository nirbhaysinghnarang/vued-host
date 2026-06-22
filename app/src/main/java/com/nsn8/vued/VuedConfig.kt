package com.nsn8.vued

/**
 * Backend + Supabase configuration. The anon (publishable) key is intentionally
 * public — Row-Level Security, not key secrecy, protects user data. The
 * service-role key must NEVER appear in the app.
 */
object VuedConfig {
    const val SUPABASE_URL = "https://wpiqcpobohslgrxzlqro.supabase.co"
    const val SUPABASE_ANON_KEY = "sb_publishable_GoKWGeYunLxg-FLh2es8TQ_CnFacnbI"

    // Stateless STT / API backend (prod). Swap for -staging / -dev as needed.
    const val API_BASE_URL = "https://vued-api-dev.onrender.com"

    // Org-management API (orgs, rooms, members) — a separate service from the
    // recording backend above. Used to fetch the org's rooms so the tablet can
    // assign itself to one.
    const val ORG_API_BASE_URL = API_BASE_URL
}
