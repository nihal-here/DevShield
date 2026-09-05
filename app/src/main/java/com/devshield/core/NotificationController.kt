package com.devshield.core

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import com.devshield.R
import com.devshield.receiver.RestoreReceiver

/**
 * Manages the persistent, ongoing notification displayed while Protection Mode is active.
 *
 * Provides a one-tap direct Restore action from the Android notification shade.
 */
class NotificationController(private val context: Context) {

    companion object {
        const val CHANNEL_ID = "devshield_banking_mode_channel" // Kept for backwards compatibility
        const val NOTIFICATION_ID = 1001
        private const val CHANNEL_NAME = "DevShield Protection Mode"
    }

    private val notificationManager =
        context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    init {
        createNotificationChannel()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                CHANNEL_NAME,
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "Shows status and restore action when Protection Mode is active."
                setShowBadge(false)
            }
            notificationManager.createNotificationChannel(channel)
        }
    }

    /**
     * Posts or updates the persistent Protection Mode notification.
     */
    fun showBankingModeNotification(targetAppLabel: String? = null) {
        val restoreIntent = Intent(context, RestoreReceiver::class.java).apply {
            action = RestoreReceiver.ACTION_RESTORE_SETTINGS
        }

        val pendingFlags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }

        val restorePendingIntent = PendingIntent.getBroadcast(
            context,
            0,
            restoreIntent,
            pendingFlags
        )

        val contentText = if (!targetAppLabel.isNullOrBlank()) {
            "Debugging flags suppressed for $targetAppLabel. Tap Restore when finished."
        } else {
            "Developer Options & USB Debugging are suppressed. Tap Restore when finished."
        }

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_shield)
            .setContentTitle("DevShield: Protection Mode Active")
            .setContentText(contentText)
            .setStyle(NotificationCompat.BigTextStyle().bigText(contentText))
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .addAction(
                android.R.drawable.ic_menu_revert,
                "Restore Settings",
                restorePendingIntent
            )
            .build()

        try {
            notificationManager.notify(NOTIFICATION_ID, notification)
        } catch (e: SecurityException) {
            // Can happen on Android 13+ if POST_NOTIFICATIONS is not granted
        }
    }

    /**
     * Cancels the Protection Mode notification upon successful restoration.
     */
    fun cancelBankingModeNotification() {
        notificationManager.cancel(NOTIFICATION_ID)
    }
}
