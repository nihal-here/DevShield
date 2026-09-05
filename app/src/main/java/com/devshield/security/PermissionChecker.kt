package com.devshield.security

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build

/**
 * Utility to verify privileged and runtime permissions required by DevShield.
 */
object PermissionChecker {

    const val PERMISSION_WRITE_SECURE_SETTINGS = Manifest.permission.WRITE_SECURE_SETTINGS

    /**
     * Checks whether WRITE_SECURE_SETTINGS has been granted to DevShield via ADB.
     */
    fun hasWriteSecureSettings(context: Context): Boolean {
        return context.checkCallingOrSelfPermission(PERMISSION_WRITE_SECURE_SETTINGS) ==
                PackageManager.PERMISSION_GRANTED
    }

    /**
     * Checks whether POST_NOTIFICATIONS is granted (required for persistent notifications on Android 13+).
     */
    fun hasPostNotifications(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.checkCallingOrSelfPermission(Manifest.permission.POST_NOTIFICATIONS) ==
                    PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
    }

    /**
     * Returns the exact ADB shell command required to grant the privileged permission.
     */
    fun getAdbGrantCommand(context: Context): String {
        return "adb shell pm grant ${context.packageName} android.permission.WRITE_SECURE_SETTINGS"
    }
}
