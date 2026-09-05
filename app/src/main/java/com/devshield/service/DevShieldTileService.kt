package com.devshield.service

import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.util.Log
import android.widget.Toast
import com.devshield.R
import com.devshield.core.NotificationController
import com.devshield.core.SettingsController
import com.devshield.core.StateRepository
import com.devshield.security.PermissionChecker
import com.devshield.ui.MainActivity

/**
 * Quick Settings TileService for DevShield.
 *
 * Allows users to toggle Protection Mode directly from Android Quick Settings
 * using the unified SettingsController snapshot, verification, and restoration logic.
 */
class DevShieldTileService : TileService() {

    companion object {
        private const val TAG = "DevShieldTileService"

        /**
         * Notifies Android SystemUI to refresh the DevShield Quick Settings tile.
         */
        fun requestTileUpdate(context: Context) {
            try {
                requestListeningState(context, ComponentName(context, DevShieldTileService::class.java))
            } catch (e: Exception) {
                Log.w(TAG, "Failed to request tile update", e)
            }
        }
    }

    override fun onStartListening() {
        super.onStartListening()
        updateTileState()
    }

    override fun onTileAdded() {
        super.onTileAdded()
        updateTileState()
    }

    override fun onClick() {
        super.onClick()
        val context = applicationContext
        val stateRepository = StateRepository(context)

        // Safety: Verify privileged permission before any modification
        if (!PermissionChecker.hasWriteSecureSettings(context)) {
            Toast.makeText(context, R.string.tile_permission_missing_toast, Toast.LENGTH_LONG).show()
            launchMainActivity()
            updateTileState()
            return
        }

        val settingsController = SettingsController(context, stateRepository)
        val notificationController = NotificationController(context)

        if (stateRepository.hasActiveSnapshot()) {
            // Tile is currently ACTIVE -> User tapped to restore
            Log.i(TAG, "Tile clicked while active. Restoring original settings...")
            val result = settingsController.restorePreviousState()
            result.fold(
                onSuccess = {
                    notificationController.cancelBankingModeNotification()
                    Toast.makeText(context, R.string.tile_restored_toast, Toast.LENGTH_SHORT).show()
                    updateTileState()
                },
                onFailure = { error ->
                    Log.e(TAG, "Failed to restore settings from tile", error)
                    Toast.makeText(context, "DevShield: ${error.message}", Toast.LENGTH_LONG).show()
                    updateTileState()
                }
            )
        } else {
            // Tile is currently INACTIVE -> User tapped to activate Protection Mode
            Log.i(TAG, "Tile clicked while inactive. Activating Protection Mode...")
            val (targetPkg, targetLabel) = stateRepository.getSavedTargetApp()
            val suppressWireless = stateRepository.isSuppressWirelessDebuggingEnabled()

            val result = settingsController.enterBankingMode(
                targetAppPackage = targetPkg,
                targetAppLabel = targetLabel,
                suppressWirelessDebugging = suppressWireless
            )

            result.fold(
                onSuccess = {
                    notificationController.showBankingModeNotification(targetLabel)
                    Toast.makeText(context, R.string.tile_activated_toast, Toast.LENGTH_SHORT).show()
                    updateTileState()

                    // Launch configured target app if set
                    if (targetPkg != null) {
                        val launchIntent = packageManager.getLaunchIntentForPackage(targetPkg)
                        if (launchIntent != null) {
                            launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            startActivity(launchIntent)
                        }
                    }
                },
                onFailure = { error ->
                    Log.e(TAG, "Failed to enter protection mode from tile", error)
                    Toast.makeText(context, "DevShield: ${error.message}", Toast.LENGTH_LONG).show()
                    updateTileState()
                }
            )
        }
    }

    fun updateTileState() {
        val tile = qsTile ?: return
        val context = applicationContext
        val stateRepository = StateRepository(context)

        val hasPermission = PermissionChecker.hasWriteSecureSettings(context)
        if (!hasPermission) {
            tile.state = Tile.STATE_UNAVAILABLE
            tile.label = getString(R.string.tile_label)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                tile.subtitle = getString(R.string.tile_subtitle_permission_required)
            }
            tile.icon = Icon.createWithResource(context, R.drawable.ic_shield)
            tile.updateTile()
            return
        }

        val isActive = stateRepository.hasActiveSnapshot()
        if (isActive) {
            tile.state = Tile.STATE_ACTIVE
            tile.label = getString(R.string.tile_label)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                tile.subtitle = getString(R.string.tile_subtitle_active)
            }
            tile.icon = Icon.createWithResource(context, R.drawable.ic_shield)
        } else {
            tile.state = Tile.STATE_INACTIVE
            tile.label = getString(R.string.tile_label)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                tile.subtitle = getString(R.string.tile_subtitle_inactive)
            }
            tile.icon = Icon.createWithResource(context, R.drawable.ic_shield)
        }
        tile.updateTile()
    }

    private fun launchMainActivity() {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                val pendingIntent = PendingIntent.getActivity(
                    this,
                    0,
                    intent,
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                )
                startActivityAndCollapse(pendingIntent)
            } else {
                @Suppress("DEPRECATION")
                startActivityAndCollapse(intent)
            }
        } catch (e: Exception) {
            Log.w(TAG, "startActivityAndCollapse failed, falling back to standard startActivity", e)
            try {
                startActivity(intent)
            } catch (fallbackError: Exception) {
                Log.e(TAG, "Failed to launch MainActivity from tile", fallbackError)
            }
        }
    }
}
