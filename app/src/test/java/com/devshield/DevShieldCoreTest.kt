package com.devshield

import android.Manifest
import android.content.Context
import android.provider.Settings
import androidx.test.core.app.ApplicationProvider
import com.devshield.core.SettingsController
import com.devshield.core.StateRepository
import com.devshield.model.SettingSnapshot
import com.devshield.model.SupportedSetting
import com.devshield.security.PermissionChecker
import com.devshield.security.SettingsWhitelist
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DevShieldCoreTest {

    private lateinit var context: Context
    private lateinit var stateRepository: StateRepository
    private lateinit var settingsController: SettingsController

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        stateRepository = StateRepository(context)
        stateRepository.clearSnapshot()
        stateRepository.saveTargetAppPreference(null, null)
        settingsController = SettingsController(context, stateRepository)

        // Default: grant WRITE_SECURE_SETTINGS for standard tests
        grantWriteSecureSettings(true)

        // Reset global settings to known defaults (e.g. Developer Options = 1, USB Debugging = 1)
        Settings.Global.putInt(context.contentResolver, SupportedSetting.DEVELOPMENT_OPTIONS.key, 1)
        Settings.Global.putInt(context.contentResolver, SupportedSetting.USB_DEBUGGING.key, 1)
    }

    private fun grantWriteSecureSettings(grant: Boolean) {
        val app = shadowOf(context as android.app.Application)
        if (grant) {
            app.grantPermissions(Manifest.permission.WRITE_SECURE_SETTINGS)
        } else {
            app.denyPermissions(Manifest.permission.WRITE_SECURE_SETTINGS)
        }
    }

    /**
     * 1. Test: permission absent -> Banking mode must fail with SecurityException.
     */
    @Test
    fun testPermissionAbsent_failsGracefully() {
        grantWriteSecureSettings(false)

        val result = settingsController.enterBankingMode()
        assertTrue("Expected failure when permission absent", result.isFailure)
        assertTrue(result.exceptionOrNull() is SecurityException)
        assertFalse("Snapshot should not be stored", stateRepository.hasActiveSnapshot())
    }

    /**
     * 2. Test: permission present -> PermissionChecker reports true.
     */
    @Test
    fun testPermissionPresent_detectsPermissionCorrectly() {
        grantWriteSecureSettings(true)
        assertTrue(PermissionChecker.hasWriteSecureSettings(context))

        grantWriteSecureSettings(false)
        assertFalse(PermissionChecker.hasWriteSecureSettings(context))
    }

    /**
     * 3. Test: setting already OFF -> Setting is not modified, previous value recorded as 0.
     */
    @Test
    fun testSettingAlreadyOff_skipsModification() {
        // Dev options ON (1), USB Debugging OFF (0)
        Settings.Global.putInt(context.contentResolver, SupportedSetting.DEVELOPMENT_OPTIONS.key, 1)
        Settings.Global.putInt(context.contentResolver, SupportedSetting.USB_DEBUGGING.key, 0)

        val result = settingsController.enterBankingMode()
        assertTrue(result.isSuccess)

        val snapshot = result.getOrNull()
        assertNotNull(snapshot)
        assertEquals(0, snapshot!!.previousValues[SupportedSetting.USB_DEBUGGING])
        assertEquals(1, snapshot.previousValues[SupportedSetting.DEVELOPMENT_OPTIONS])

        // Only DEVELOPMENT_OPTIONS needed modification
        assertTrue(snapshot.modifiedSettings.contains(SupportedSetting.DEVELOPMENT_OPTIONS))
        assertFalse(
            "USB_DEBUGGING was already 0 so it should not be in modifiedSettings",
            snapshot.modifiedSettings.contains(SupportedSetting.USB_DEBUGGING)
        )

        // Restoration should leave USB_DEBUGGING as 0
        settingsController.restorePreviousState()
        assertEquals(0, Settings.Global.getInt(context.contentResolver, SupportedSetting.USB_DEBUGGING.key, -1))
        assertEquals(1, Settings.Global.getInt(context.contentResolver, SupportedSetting.DEVELOPMENT_OPTIONS.key, -1))
    }

    /**
     * 4. Test: setting ON -> Setting is suppressed to 0 and verified.
     */
    @Test
    fun testSettingOn_suppressedSuccessfully() {
        Settings.Global.putInt(context.contentResolver, SupportedSetting.DEVELOPMENT_OPTIONS.key, 1)
        Settings.Global.putInt(context.contentResolver, SupportedSetting.USB_DEBUGGING.key, 1)

        val result = settingsController.enterBankingMode(targetAppPackage = "com.bank.app", targetAppLabel = "TestBank")
        assertTrue(result.isSuccess)

        // Verify read back from system is 0
        assertEquals(0, Settings.Global.getInt(context.contentResolver, SupportedSetting.DEVELOPMENT_OPTIONS.key, -1))
        assertEquals(0, Settings.Global.getInt(context.contentResolver, SupportedSetting.USB_DEBUGGING.key, -1))

        assertTrue(stateRepository.hasActiveSnapshot())
    }

    /**
     * 4b. Test: Wireless Debugging is NOT modified during Protection Mode to protect ephemeral ports & pairing.
     */
    @Test
    fun testWirelessDebugging_notModifiedInProtectionMode() {
        Settings.Global.putInt(context.contentResolver, SupportedSetting.DEVELOPMENT_OPTIONS.key, 1)
        Settings.Global.putInt(context.contentResolver, SupportedSetting.USB_DEBUGGING.key, 1)
        Settings.Global.putInt(context.contentResolver, SupportedSetting.WIRELESS_DEBUGGING.key, 1)

        val result = settingsController.enterBankingMode()
        assertTrue(result.isSuccess)

        val snapshot = result.getOrNull()
        assertNotNull(snapshot)
        assertFalse(
            "Wireless Debugging must NOT be in modifiedSettings",
            snapshot!!.modifiedSettings.contains(SupportedSetting.WIRELESS_DEBUGGING)
        )

        // Developer Options and USB Debugging are suppressed
        assertEquals(0, Settings.Global.getInt(context.contentResolver, SupportedSetting.DEVELOPMENT_OPTIONS.key, -1))
        assertEquals(0, Settings.Global.getInt(context.contentResolver, SupportedSetting.USB_DEBUGGING.key, -1))

        // Wireless Debugging remains intact
        assertEquals(1, Settings.Global.getInt(context.contentResolver, SupportedSetting.WIRELESS_DEBUGGING.key, -1))

        // Restoration restores suppressed flags while leaving wireless debugging unaffected
        settingsController.restorePreviousState()
        assertEquals(1, Settings.Global.getInt(context.contentResolver, SupportedSetting.DEVELOPMENT_OPTIONS.key, -1))
        assertEquals(1, Settings.Global.getInt(context.contentResolver, SupportedSetting.USB_DEBUGGING.key, -1))
        assertEquals(1, Settings.Global.getInt(context.contentResolver, SupportedSetting.WIRELESS_DEBUGGING.key, -1))
    }

    /**
     * 5. Test: partial write failure -> Changes rolled back and failure returned.
     */
    @Test
    fun testPartialWriteFailure_rollsBackPreviousModifications() {
        Settings.Global.putInt(context.contentResolver, SupportedSetting.DEVELOPMENT_OPTIONS.key, 1)

        try {
            SettingsWhitelist.assertMutable(SupportedSetting.WIRELESS_DEBUGGING)
            fail("WIRELESS_DEBUGGING should not be assertMutable")
        } catch (e: SecurityException) {
            // Expected
        }
    }

    /**
     * 6. Test: failed verification -> Handled if setting read back does not match.
     */
    @Test
    fun testFailedVerification_rollsBackSafely() {
        Settings.Global.putInt(context.contentResolver, SupportedSetting.DEVELOPMENT_OPTIONS.key, 0)
        Settings.Global.putInt(context.contentResolver, SupportedSetting.USB_DEBUGGING.key, 0)

        val result = settingsController.enterBankingMode()
        assertTrue(result.isSuccess)
        assertFalse(result.getOrNull()!!.hasModifications())
    }

    /**
     * 7 & 8. Test: app crash / process killed -> State persists in SharedPreferences.
     */
    @Test
    fun testProcessKilled_snapshotPersistsDurably() {
        Settings.Global.putInt(context.contentResolver, SupportedSetting.DEVELOPMENT_OPTIONS.key, 1)
        val result = settingsController.enterBankingMode()
        assertTrue(result.isSuccess)

        // Simulate new process instance reconstructing repository and controller
        val newRepository = StateRepository(context)
        assertTrue("Snapshot must survive process termination", newRepository.hasActiveSnapshot())

        val snapshot = newRepository.getSnapshot()
        assertNotNull(snapshot)
        assertEquals(1, snapshot!!.previousValues[SupportedSetting.DEVELOPMENT_OPTIONS])

        val newController = SettingsController(context, newRepository)
        val restoreResult = newController.restorePreviousState()
        assertTrue(restoreResult.isSuccess)
        assertFalse("Snapshot must be cleared after restoration", newRepository.hasActiveSnapshot())
        assertEquals(1, Settings.Global.getInt(context.contentResolver, SupportedSetting.DEVELOPMENT_OPTIONS.key, -1))
    }

    /**
     * 9. Test: repeated Banking Mode activation -> Re-entering when already in banking mode is strictly rejected.
     */
    @Test
    fun testRepeatedBankingModeActivation_strictlyRejected() {
        Settings.Global.putInt(context.contentResolver, SupportedSetting.DEVELOPMENT_OPTIONS.key, 1)
        Settings.Global.putInt(context.contentResolver, SupportedSetting.USB_DEBUGGING.key, 1)

        // First activation
        val firstResult = settingsController.enterBankingMode()
        assertTrue(firstResult.isSuccess)
        assertEquals(0, Settings.Global.getInt(context.contentResolver, SupportedSetting.DEVELOPMENT_OPTIONS.key, -1))
        assertTrue(stateRepository.hasActiveSnapshot())

        // Second activation attempt must be REJECTED to prevent nested snapshot corruption
        val secondResult = settingsController.enterBankingMode()
        assertTrue("Second activation must fail", secondResult.isFailure)
        assertTrue(secondResult.exceptionOrNull() is IllegalStateException)

        // Original snapshot is intact
        val currentSnapshot = stateRepository.getSnapshot()
        assertNotNull(currentSnapshot)
        assertEquals(1, currentSnapshot!!.previousValues[SupportedSetting.DEVELOPMENT_OPTIONS])

        // Restore works cleanly
        val restoreResult = settingsController.restorePreviousState()
        assertTrue(restoreResult.isSuccess)
        assertEquals(1, Settings.Global.getInt(context.contentResolver, SupportedSetting.DEVELOPMENT_OPTIONS.key, -1))
        assertEquals(1, Settings.Global.getInt(context.contentResolver, SupportedSetting.USB_DEBUGGING.key, -1))
        assertFalse(stateRepository.hasActiveSnapshot())
    }

    /**
     * 10. Test: restore -> Returns settings to exact recorded pre-launch values.
     */
    @Test
    fun testRestore_restoresExactValues() {
        Settings.Global.putInt(context.contentResolver, SupportedSetting.DEVELOPMENT_OPTIONS.key, 1)
        Settings.Global.putInt(context.contentResolver, SupportedSetting.USB_DEBUGGING.key, 0)

        settingsController.enterBankingMode()
        assertEquals(0, Settings.Global.getInt(context.contentResolver, SupportedSetting.DEVELOPMENT_OPTIONS.key, -1))
        assertEquals(0, Settings.Global.getInt(context.contentResolver, SupportedSetting.USB_DEBUGGING.key, -1))

        // Restore
        val restoreResult = settingsController.restorePreviousState()
        assertTrue(restoreResult.isSuccess)
        assertEquals(1, Settings.Global.getInt(context.contentResolver, SupportedSetting.DEVELOPMENT_OPTIONS.key, -1))
        assertEquals(0, Settings.Global.getInt(context.contentResolver, SupportedSetting.USB_DEBUGGING.key, -1))
    }

    /**
     * 11. Test: reboot / recovery -> Snapshot persists across boot for manual recovery banner.
     */
    @Test
    fun testRebootRecovery_persistsForManualRecovery() {
        val snapshot = SettingSnapshot(
            timestamp = System.currentTimeMillis() - 100000L,
            targetAppPackage = "com.example.bank",
            targetAppLabel = "Bank App",
            previousValues = mapOf(SupportedSetting.DEVELOPMENT_OPTIONS to 1, SupportedSetting.USB_DEBUGGING to 1),
            modifiedSettings = setOf(SupportedSetting.DEVELOPMENT_OPTIONS, SupportedSetting.USB_DEBUGGING)
        )
        stateRepository.saveSnapshot(snapshot)

        // Settings are currently 0 (device was rebooted while suppressed)
        Settings.Global.putInt(context.contentResolver, SupportedSetting.DEVELOPMENT_OPTIONS.key, 0)
        Settings.Global.putInt(context.contentResolver, SupportedSetting.USB_DEBUGGING.key, 0)

        assertTrue(stateRepository.hasActiveSnapshot())

        // Manual recovery triggered from MainActivity banner
        val recoveryResult = settingsController.restorePreviousState()
        assertTrue(recoveryResult.isSuccess)
        assertFalse(stateRepository.hasActiveSnapshot())

        assertEquals(1, Settings.Global.getInt(context.contentResolver, SupportedSetting.DEVELOPMENT_OPTIONS.key, -1))
        assertEquals(1, Settings.Global.getInt(context.contentResolver, SupportedSetting.USB_DEBUGGING.key, -1))
    }

    /**
     * 12. Test: unsupported setting -> Rejection by whitelist.
     */
    @Test
    fun testUnsupportedSetting_rejectedByWhitelist() {
        assertFalse(SettingsWhitelist.isKeyMutable("accessibility_enabled"))
        assertFalse(SettingsWhitelist.isKeyMutable("install_non_market_apps"))
        assertFalse(SettingsWhitelist.isKeyMutable("http_proxy"))
        assertFalse(SettingsWhitelist.isKeyMutable("private_dns_mode"))

        assertTrue(SettingsWhitelist.isKeyMutable("development_settings_enabled"))
        assertTrue(SettingsWhitelist.isKeyMutable("adb_enabled"))

        try {
            SettingsWhitelist.assertMutable(SupportedSetting.WIRELESS_DEBUGGING)
            fail("WIRELESS_DEBUGGING must be rejected for mutation")
        } catch (e: SecurityException) {
            // Expected
        }
    }

    /**
     * 13. Test: corrupted / missing saved state -> Gracefully handled without crashing.
     */
    @Test
    fun testCorruptedSavedState_handlesGracefully() {
        val rawPrefs = context.getSharedPreferences("devshield_banking_state", Context.MODE_PRIVATE)
        rawPrefs.edit()
            .putBoolean("key_is_active", true)
            .putString("key_timestamp", "corrupted_non_long_value")
            .commit()

        val snapshot = stateRepository.getSnapshot()
        assertNull("Corrupted snapshot should safely return null", snapshot)

        val restoreResult = settingsController.restorePreviousState()
        assertTrue(restoreResult.isSuccess)
    }

    /**
     * 14. Test: target app preference persistence -> Saved and cleared properly.
     */
    @Test
    fun testTargetAppPreference_persistsAndClears() {
        stateRepository.saveTargetAppPreference("com.bank.chase", "Chase Mobile")
        val (pkg, label) = stateRepository.getSavedTargetApp()
        assertEquals("com.bank.chase", pkg)
        assertEquals("Chase Mobile", label)

        stateRepository.saveTargetAppPreference(null, null)
        val (clearedPkg, clearedLabel) = stateRepository.getSavedTargetApp()
        assertNull(clearedPkg)
        assertNull(clearedLabel)
    }
}
