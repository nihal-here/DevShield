package com.devshield.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import android.widget.Toast
import com.devshield.core.NotificationController
import com.devshield.core.SettingsController

/**
 * Non-exported BroadcastReceiver triggered when the user taps "Restore Settings"
 * from the persistent notification.
 */
class RestoreReceiver : BroadcastReceiver() {

    companion object {
        const val ACTION_RESTORE_SETTINGS = "com.devshield.action.RESTORE_SETTINGS"
        private const val TAG = "RestoreReceiver"
    }

    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != ACTION_RESTORE_SETTINGS) return

        Log.i(TAG, "Received request to restore settings from notification.")

        val controller = SettingsController(context)
        val result = controller.restorePreviousState()

        val notificationController = NotificationController(context)

        result.fold(
            onSuccess = {
                notificationController.cancelBankingModeNotification()
                com.devshield.service.DevShieldTileService.requestTileUpdate(context)
                Toast.makeText(context, "DevShield: Settings restored successfully.", Toast.LENGTH_SHORT).show()
            },
            onFailure = { error ->
                Log.e(TAG, "Restoration failed", error)
                Toast.makeText(
                    context,
                    "DevShield: Failed to restore settings: ${error.message}",
                    Toast.LENGTH_LONG
                ).show()
            }
        )
    }
}
