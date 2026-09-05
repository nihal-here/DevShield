package com.devshield

import android.app.Application
import com.devshield.core.NotificationController

/**
 * DevShield Application entry point.
 *
 * Ensures notification channels are registered upon application initialization.
 */
class DevShieldApp : Application() {

    override fun onCreate() {
        super.onCreate()
        // Ensure notification channels exist
        NotificationController(this)
    }
}
