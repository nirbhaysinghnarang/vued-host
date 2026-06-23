package com.nsn8.vued

import android.app.Application

/**
 * Process entry point. The tablet is now an upload/provisioning appliance; it no
 * longer exposes a LAN decrypt server for desktop/web clients.
 */
class App : Application() {
    override fun onCreate() {
        super.onCreate()
    }
}
