package com.devshield.core

import android.content.ContentResolver
import android.content.Context
import android.provider.Settings
import android.util.Log
import com.devshield.model.SettingNamespace
import com.devshield.model.SettingSnapshot
import com.devshield.model.SupportedSetting
import com.devshield.security.PermissionChecker
import com.devshield.security.SettingsWhitelist

/**
 * Encapsulates all interactions with the Android SettingsProvider.
 *
 * Enforces strict compile-time whitelisting, atomic snapshotting prior to modification,
 * and verified writes/restorations for Banking Mode.
 */
class SettingsController(
    private val context: Context,
    private val stateRepository: StateRepository = StateRepository(context),
    private val contentResolver: ContentResolver = context.contentResolver
) {

    companion object {
        private const val TAG = "SettingsController"
    }

    /**
     * Reads the current integer state of any supported setting.
     */
    fun readSetting(setting: SupportedSetting): Int {
        return when (setting.namespace) {
            SettingNamespace.GLOBAL -> {
                Settings.Global.getInt(contentResolver, setting.key, 0)
            }
            SettingNamespace.SECURE -> {
                Settings.Secure.getInt(contentResolver, setting.key, 0)
            }
        }
    }

    /**
     * Gathers the current diagnostic values for all defined settings.
     */
    fun getDiagnosticState(): Map<SupportedSetting, Int> {
        return SupportedSetting.entries.associateWith { readSetting(it) }
    }

    /**
     * Executes the Banking Mode entry protocol:
     * 1. Validates privileged permission.
     * 2. Checks that no active snapshot is already pending (only one active session permitted).
     * 3. Reads current values of all suppressible settings.
     * 4. Persists the snapshot durably BEFORE performing modifications.
     * 5. Modifies only settings that currently require disabling (value != 0).
     * 6. Reads back and verifies each write.
     * 7. If any modification or verification fails, performs rollback and clears snapshot if clean.
     */
    @Synchronized
    fun enterBankingMode(
        targetAppPackage: String? = null,
        targetAppLabel: String? = null,
        suppressWirelessDebugging: Boolean = stateRepository.isSuppressWirelessDebuggingEnabled()
    ): Result<SettingSnapshot> {
        if (!PermissionChecker.hasWriteSecureSettings(context)) {
            return Result.failure(
                SecurityException("WRITE_SECURE_SETTINGS permission is not granted. Cannot enter Protection Mode.")
            )
        }

        if (stateRepository.hasActiveSnapshot()) {
            return Result.failure(
                IllegalStateException("Protection Mode is already active. Please restore previous settings before re-entering.")
            )
        }

        val suppressible = SupportedSetting.suppressibleSettings.filter { setting ->
            if (setting == SupportedSetting.WIRELESS_DEBUGGING) {
                suppressWirelessDebugging
            } else {
                true
            }
        }
        val currentValues = mutableMapOf<SupportedSetting, Int>()
        val settingsToModify = mutableListOf<SupportedSetting>()

        // 1. Read current state of all supported settings for snapshotting
        for (setting in SupportedSetting.entries) {
            val currentValue = readSetting(setting)
            currentValues[setting] = currentValue
            if (setting in suppressible && currentValue != 0) {
                settingsToModify.add(setting)
            }
        }

        val snapshot = SettingSnapshot(
            timestamp = System.currentTimeMillis(),
            targetAppPackage = targetAppPackage,
            targetAppLabel = targetAppLabel,
            previousValues = currentValues,
            modifiedSettings = settingsToModify.toSet()
        )

        // If nothing needs modification, no settings change is required
        if (settingsToModify.isEmpty()) {
            return Result.success(snapshot)
        }

        // 2. Persist snapshot FIRST so state is durable even if process dies mid-write
        stateRepository.saveSnapshot(snapshot)

        val successfullyModified = mutableSetOf<SupportedSetting>()

        // 3. Modify settings to 0
        for (setting in settingsToModify) {
            SettingsWhitelist.assertMutable(setting)

            val writeSuccess = try {
                Settings.Global.putInt(contentResolver, setting.key, 0)
            } catch (e: Exception) {
                Log.e(TAG, "Exception writing setting: ${setting.key}", e)
                false
            }

            if (!writeSuccess) {
                val rollbackClean = rollback(successfullyModified, currentValues)
                if (rollbackClean) {
                    stateRepository.clearSnapshot()
                }
                return Result.failure(
                    IllegalStateException("Failed to write setting '${setting.label}'. Modifications rolled back.")
                )
            }

            successfullyModified.add(setting)

            // 4. Verification: Read back immediately
            val verifiedValue = readSetting(setting)
            if (verifiedValue != 0) {
                val rollbackClean = rollback(successfullyModified, currentValues)
                if (rollbackClean) {
                    stateRepository.clearSnapshot()
                }
                return Result.failure(
                    IllegalStateException("Verification failed for '${setting.label}': read back $verifiedValue instead of 0. Modifications rolled back.")
                )
            }
        }

        // Notify state repository observers that settings writes have completed and verified
        stateRepository.notifyStateChanged()

        return Result.success(snapshot)
    }

    /**
     * Executes the state restoration protocol:
     * 1. Retrieves saved snapshot from storage.
     * 2. Validates permission.
     * 3. Restores exact previous values for all settings modified by DevShield.
     * 4. Reads back and verifies each restored setting.
     * 5. Clears the persistent snapshot only after full verification.
     */
    @Synchronized
    fun restorePreviousState(): Result<Unit> {
        val snapshot = stateRepository.getSnapshot()
            ?: return Result.success(Unit) // Idempotent: nothing active to restore

        if (!PermissionChecker.hasWriteSecureSettings(context)) {
            return Result.failure(
                SecurityException("WRITE_SECURE_SETTINGS permission missing. Cannot restore previous settings.")
            )
        }

        val failedRestorations = mutableListOf<String>()

        for (setting in snapshot.modifiedSettings) {
            SettingsWhitelist.assertMutable(setting)

            val targetValue = snapshot.previousValues[setting] ?: 0

            val writeSuccess = try {
                Settings.Global.putInt(contentResolver, setting.key, targetValue)
            } catch (e: Exception) {
                Log.e(TAG, "Exception restoring setting: ${setting.key}", e)
                false
            }

            if (!writeSuccess) {
                failedRestorations.add("${setting.label} (write error)")
                continue
            }

            // Verify read-back
            val readBack = readSetting(setting)
            if (readBack != targetValue) {
                failedRestorations.add("${setting.label} (verified: $readBack, expected: $targetValue)")
            }
        }

        return if (failedRestorations.isEmpty()) {
            stateRepository.clearSnapshot()
            Result.success(Unit)
        } else {
            Result.failure(
                IllegalStateException("Restoration failed for: ${failedRestorations.joinToString(", ")}. Snapshot retained for recovery.")
            )
        }
    }

    /**
     * Executes a safe diagnostic write test strictly bounded to the whitelist.
     * Reads current value of Developer Options, writes the identical value back, and verifies read-back.
     */
    fun runDiagnosticWriteTest(): Result<Int> {
        if (!PermissionChecker.hasWriteSecureSettings(context)) {
            return Result.failure(
                SecurityException("WRITE_SECURE_SETTINGS permission is required to run write test.")
            )
        }

        val setting = SupportedSetting.DEVELOPMENT_OPTIONS
        SettingsWhitelist.assertMutable(setting)

        val currentValue = readSetting(setting)
        val writeSuccess = try {
            Settings.Global.putInt(contentResolver, setting.key, currentValue)
        } catch (e: Exception) {
            return Result.failure(e)
        }

        val readBack = readSetting(setting)
        return if (writeSuccess && readBack == currentValue) {
            Result.success(readBack)
        } else {
            Result.failure(
                IllegalStateException("Diagnostic write test failed: wrote $currentValue, read back $readBack")
            )
        }
    }

    /**
     * Internal rollback mechanism invoked upon partial failure during Banking Mode entry.
     * Returns true if all modified settings were successfully verified back to their original values.
     */
    private fun rollback(
        modifiedSettings: Set<SupportedSetting>,
        previousValues: Map<SupportedSetting, Int>
    ): Boolean {
        Log.w(TAG, "Rolling back ${modifiedSettings.size} modified settings...")
        var allVerified = true
        for (setting in modifiedSettings) {
            try {
                val originalValue = previousValues[setting] ?: 0
                val writeSuccess = Settings.Global.putInt(contentResolver, setting.key, originalValue)
                val readBack = readSetting(setting)
                if (!writeSuccess || readBack != originalValue) {
                    allVerified = false
                    Log.e(TAG, "Rollback verification failed for ${setting.key}: expected $originalValue, got $readBack")
                }
            } catch (e: Exception) {
                allVerified = false
                Log.e(TAG, "Error during rollback of ${setting.key}", e)
            }
        }
        return allVerified
    }
}
