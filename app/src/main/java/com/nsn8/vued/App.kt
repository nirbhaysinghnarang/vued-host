package com.nsn8.vued

import android.app.Application
import com.nsn8.vued.net.DecryptGateway

/**
 * Process entry point. Brings the LAN decrypt gateway up on launch so it's
 * available by default — not gated on recording. onCreate runs before any
 * activity or service in this process, so the gateway is always started first.
 */
class App : Application() {
    override fun onCreate() {
        super.onCreate()
        DecryptGateway.start(this)
    }
}
