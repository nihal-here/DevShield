package com.devshield.core

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.devshield.model.SettingSnapshot
import com.devshield.model.SupportedSetting

/**
 * Manages persistent storage of Banking Mode state snapshots in private SharedPreferences.
 *
 * Guarantees atomic writes and resilient handling of missing or corrupted state files.
 */
class StateRepository(context: Context) {

    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    companion object {
        private const val TAG = "StateRepository"
        private const val PREFS_NAME = "devshield_banking_state"
        private const val KEY_ACTIVE = "key_is_active"
        private const val KEY_TIMESTAMP = "key_timestamp"
        private const val KEY_TARGET_PACKAGE = "key_target_package"
        private const val KEY_TARGET_LABEL = "key_target_label"
        private const val PREFIX_PREV = "prev_"
        private const val PREFIX_MODIFIED = "mod_"

        private const val KEY_SAVED_TARGET_PKG = "saved_target_pkg"
        private const val KEY_SAVED_TARGET_LABEL = "saved_target_label"
        private const val KEY_SUPPRESS_WIRELESS = "suppress_wireless_debugging"
    }

    /**
     * Checks whether an un-restored Banking Mode snapshot exists.
     */
    fun hasActiveSnapshot(): Boolean {
        return prefs.getBoolean(KEY_ACTIVE, false)
    }

    /**
     * Persists the exact pre-launch values and marks which settings were modified.
     */
    fun saveSnapshot(snapshot: SettingSnapshot) {
        val editor = prefs.edit()
        editor.putBoolean(KEY_ACTIVE, true)
        editor.putLong(KEY_TIMESTAMP, snapshot.timestamp)
        editor.putString(KEY_TARGET_PACKAGE, snapshot.targetAppPackage)
        editor.putString(KEY_TARGET_LABEL, snapshot.targetAppLabel)

        for (setting in SupportedSetting.entries) {
            val prevVal = snapshot.previousValues[setting]
            if (prevVal != null) {
                editor.putInt(PREFIX_PREV + setting.key, prevVal)
            } else {
                editor.remove(PREFIX_PREV + setting.key)
            }

            val wasModified = snapshot.modifiedSettings.contains(setting)
            editor.putBoolean(PREFIX_MODIFIED + setting.key, wasModified)
        }

        val success = editor.commit() // Use commit() for immediate durability
        if (!success) {
            Log.e(TAG, "Failed to commit Banking Mode snapshot to SharedPreferences!")
        }
    }

    /**
     * Retrieves the stored snapshot, or null if no snapshot exists or data is corrupted.
     */
    fun getSnapshot(): SettingSnapshot? {
        if (!hasActiveSnapshot()) {
            return null
        }

        return try {
            val timestamp = prefs.getLong(KEY_TIMESTAMP, 0L)
            val targetPkg = prefs.getString(KEY_TARGET_PACKAGE, null)
            val targetLabel = prefs.getString(KEY_TARGET_LABEL, null)

            val previousValues = mutableMapOf<SupportedSetting, Int>()
            val modifiedSettings = mutableSetOf<SupportedSetting>()

            for (setting in SupportedSetting.entries) {
                val prevKey = PREFIX_PREV + setting.key
                if (prefs.contains(prevKey)) {
                    previousValues[setting] = prefs.getInt(prevKey, 0)
                }

                val modKey = PREFIX_MODIFIED + setting.key
                if (prefs.getBoolean(modKey, false)) {
                    modifiedSettings.add(setting)
                }
            }

            SettingSnapshot(
                timestamp = timestamp,
                targetAppPackage = targetPkg,
                targetAppLabel = targetLabel,
                previousValues = previousValues,
                modifiedSettings = modifiedSettings
            )
        } catch (e: Exception) {
            Log.e(TAG, "Encountered corrupted snapshot in SharedPreferences", e)
            null
        }
    }

    /**
     * Clears the active snapshot after successful verification of restoration.
     */
    fun clearSnapshot(): Boolean {
        val editor = prefs.edit()
        editor.remove(KEY_ACTIVE)
        editor.remove(KEY_TIMESTAMP)
        editor.remove(KEY_TARGET_PACKAGE)
        editor.remove(KEY_TARGET_LABEL)
        for (setting in SupportedSetting.entries) {
            editor.remove(PREFIX_PREV + setting.key)
            editor.remove(PREFIX_MODIFIED + setting.key)
        }
        return editor.commit()
    }

    /**
     * Persists the user's preferred target banking app selection.
     */
    fun saveTargetAppPreference(packageName: String?, label: String?) {
        val editor = prefs.edit()
        if (packageName != null) {
            editor.putString(KEY_SAVED_TARGET_PKG, packageName)
            editor.putString(KEY_SAVED_TARGET_LABEL, label)
        } else {
            editor.remove(KEY_SAVED_TARGET_PKG)
            editor.remove(KEY_SAVED_TARGET_LABEL)
        }
        editor.apply()
    }

    /**
     * Retrieves the persisted target banking app selection.
     */
    fun getSavedTargetApp(): Pair<String?, String?> {
        val pkg = prefs.getString(KEY_SAVED_TARGET_PKG, null)
        val label = prefs.getString(KEY_SAVED_TARGET_LABEL, null)
        return Pair(pkg, label)
    }

    /**
     * Checks whether Wireless Debugging suppression is enabled (default: true).
     */
    fun isSuppressWirelessDebuggingEnabled(): Boolean {
        return prefs.getBoolean(KEY_SUPPRESS_WIRELESS, true)
    }

    /**
     * Persists the user's preference for suppressing Wireless Debugging during Protection Mode.
     */
    fun setSuppressWirelessDebuggingEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_SUPPRESS_WIRELESS, enabled).apply()
    }
}
