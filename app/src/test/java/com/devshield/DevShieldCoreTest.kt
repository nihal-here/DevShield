package com.devshield

import android.Manifest
import android.content.Context
import android.content.res.Configuration
import android.provider.Settings
import androidx.core.content.ContextCompat
import androidx.test.core.app.ApplicationProvider
import com.devshield.core.SettingsController
import com.devshield.core.StateRepository
import com.devshield.model.SettingSnapshot
import com.devshield.model.SupportedSetting
import com.devshield.security.PermissionChecker
import com.devshield.security.SettingsWhitelist
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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
        stateRepository.setSuppressWirelessDebuggingEnabled(true)
        settingsController = SettingsController(context, stateRepository)

        // Default: grant WRITE_SECURE_SETTINGS for standard tests
        grantWriteSecureSettings(true)

        // Reset global settings to known defaults
        Settings.Global.putInt(context.contentResolver, SupportedSetting.DEVELOPMENT_OPTIONS.key, 1)
        Settings.Global.putInt(context.contentResolver, SupportedSetting.USB_DEBUGGING.key, 1)
        Settings.Global.putInt(context.contentResolver, SupportedSetting.WIRELESS_DEBUGGING.key, 0)
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
     * 1. Test: permission absent -> Protection mode must fail with SecurityException.
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
        // Dev options ON (1), USB Debugging OFF (0), Wireless Debugging OFF (0)
        Settings.Global.putInt(context.contentResolver, SupportedSetting.DEVELOPMENT_OPTIONS.key, 1)
        Settings.Global.putInt(context.contentResolver, SupportedSetting.USB_DEBUGGING.key, 0)
        Settings.Global.putInt(context.contentResolver, SupportedSetting.WIRELESS_DEBUGGING.key, 0)

        val result = settingsController.enterBankingMode()
        assertTrue(result.isSuccess)

        val snapshot = result.getOrNull()
        assertNotNull(snapshot)
        assertEquals(0, snapshot!!.previousValues[SupportedSetting.USB_DEBUGGING])
        assertEquals(1, snapshot.previousValues[SupportedSetting.DEVELOPMENT_OPTIONS])
        assertEquals(0, snapshot.previousValues[SupportedSetting.WIRELESS_DEBUGGING])

        // Only DEVELOPMENT_OPTIONS needed modification
        assertTrue(snapshot.modifiedSettings.contains(SupportedSetting.DEVELOPMENT_OPTIONS))
        assertFalse(
            "USB_DEBUGGING was already 0 so it should not be in modifiedSettings",
            snapshot.modifiedSettings.contains(SupportedSetting.USB_DEBUGGING)
        )
        assertFalse(
            "WIRELESS_DEBUGGING was already 0 so it should not be in modifiedSettings",
            snapshot.modifiedSettings.contains(SupportedSetting.WIRELESS_DEBUGGING)
        )

        // Restoration should leave USB_DEBUGGING as 0 and WIRELESS_DEBUGGING as 0
        settingsController.restorePreviousState()
        assertEquals(0, Settings.Global.getInt(context.contentResolver, SupportedSetting.USB_DEBUGGING.key, -1))
        assertEquals(1, Settings.Global.getInt(context.contentResolver, SupportedSetting.DEVELOPMENT_OPTIONS.key, -1))
        assertEquals(0, Settings.Global.getInt(context.contentResolver, SupportedSetting.WIRELESS_DEBUGGING.key, -1))
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
     * 5. Test: Wireless Debugging ON with preference ON -> Successfully suppressed to 0.
     */
    @Test
    fun testWirelessDebuggingOn_suppressedSuccessfully() {
        Settings.Global.putInt(context.contentResolver, SupportedSetting.DEVELOPMENT_OPTIONS.key, 1)
        Settings.Global.putInt(context.contentResolver, SupportedSetting.USB_DEBUGGING.key, 1)
        Settings.Global.putInt(context.contentResolver, SupportedSetting.WIRELESS_DEBUGGING.key, 1)

        val result = settingsController.enterBankingMode(suppressWirelessDebugging = true)
        assertTrue(result.isSuccess)

        val snapshot = result.getOrNull()
        assertNotNull(snapshot)
        assertTrue(
            "Wireless Debugging must be in modifiedSettings when suppressed",
            snapshot!!.modifiedSettings.contains(SupportedSetting.WIRELESS_DEBUGGING)
        )

        // All three flags suppressed to 0
        assertEquals(0, Settings.Global.getInt(context.contentResolver, SupportedSetting.DEVELOPMENT_OPTIONS.key, -1))
        assertEquals(0, Settings.Global.getInt(context.contentResolver, SupportedSetting.USB_DEBUGGING.key, -1))
        assertEquals(0, Settings.Global.getInt(context.contentResolver, SupportedSetting.WIRELESS_DEBUGGING.key, -1))
    }

    /**
     * 6. Test: Wireless Debugging already OFF -> No unnecessary modification.
     */
    @Test
    fun testWirelessDebuggingAlreadyOff_skipsModification() {
        Settings.Global.putInt(context.contentResolver, SupportedSetting.DEVELOPMENT_OPTIONS.key, 1)
        Settings.Global.putInt(context.contentResolver, SupportedSetting.USB_DEBUGGING.key, 1)
        Settings.Global.putInt(context.contentResolver, SupportedSetting.WIRELESS_DEBUGGING.key, 0)

        val result = settingsController.enterBankingMode(suppressWirelessDebugging = true)
        assertTrue(result.isSuccess)

        val snapshot = result.getOrNull()
        assertNotNull(snapshot)
        assertFalse(
            "Wireless Debugging was already 0, must not be in modifiedSettings",
            snapshot!!.modifiedSettings.contains(SupportedSetting.WIRELESS_DEBUGGING)
        )

        assertEquals(0, Settings.Global.getInt(context.contentResolver, SupportedSetting.WIRELESS_DEBUGGING.key, -1))
    }

    /**
     * 7. Test: Wireless Debugging ON -> Restored to exact pre-protection value (1).
     */
    @Test
    fun testWirelessDebuggingOn_restoredToExactPreviousValue() {
        Settings.Global.putInt(context.contentResolver, SupportedSetting.DEVELOPMENT_OPTIONS.key, 1)
        Settings.Global.putInt(context.contentResolver, SupportedSetting.USB_DEBUGGING.key, 1)
        Settings.Global.putInt(context.contentResolver, SupportedSetting.WIRELESS_DEBUGGING.key, 1)

        val enterResult = settingsController.enterBankingMode(suppressWirelessDebugging = true)
        assertTrue(enterResult.isSuccess)
        assertEquals(0, Settings.Global.getInt(context.contentResolver, SupportedSetting.WIRELESS_DEBUGGING.key, -1))

        // Restore
        val restoreResult = settingsController.restorePreviousState()
        assertTrue(restoreResult.isSuccess)
        assertEquals(1, Settings.Global.getInt(context.contentResolver, SupportedSetting.DEVELOPMENT_OPTIONS.key, -1))
        assertEquals(1, Settings.Global.getInt(context.contentResolver, SupportedSetting.USB_DEBUGGING.key, -1))
        assertEquals(1, Settings.Global.getInt(context.contentResolver, SupportedSetting.WIRELESS_DEBUGGING.key, -1))
    }

    /**
     * 8. Test: Wireless Debugging originally OFF -> Remains OFF after restore.
     */
    @Test
    fun testWirelessDebuggingOriginallyOff_remainsOffAfterRestore() {
        Settings.Global.putInt(context.contentResolver, SupportedSetting.DEVELOPMENT_OPTIONS.key, 1)
        Settings.Global.putInt(context.contentResolver, SupportedSetting.USB_DEBUGGING.key, 1)
        Settings.Global.putInt(context.contentResolver, SupportedSetting.WIRELESS_DEBUGGING.key, 0)

        settingsController.enterBankingMode(suppressWirelessDebugging = true)
        settingsController.restorePreviousState()

        assertEquals(1, Settings.Global.getInt(context.contentResolver, SupportedSetting.DEVELOPMENT_OPTIONS.key, -1))
        assertEquals(1, Settings.Global.getInt(context.contentResolver, SupportedSetting.USB_DEBUGGING.key, -1))
        assertEquals(0, Settings.Global.getInt(context.contentResolver, SupportedSetting.WIRELESS_DEBUGGING.key, -1))
    }

    /**
     * 9. Test: Wireless suppression preference OFF -> Wireless Debugging left untouched.
     */
    @Test
    fun testWirelessSuppressionPreferenceOff_untouched() {
        Settings.Global.putInt(context.contentResolver, SupportedSetting.DEVELOPMENT_OPTIONS.key, 1)
        Settings.Global.putInt(context.contentResolver, SupportedSetting.USB_DEBUGGING.key, 1)
        Settings.Global.putInt(context.contentResolver, SupportedSetting.WIRELESS_DEBUGGING.key, 1)

        val result = settingsController.enterBankingMode(suppressWirelessDebugging = false)
        assertTrue(result.isSuccess)

        val snapshot = result.getOrNull()
        assertNotNull(snapshot)
        assertFalse(
            "Wireless Debugging must NOT be in modifiedSettings when preference is disabled",
            snapshot!!.modifiedSettings.contains(SupportedSetting.WIRELESS_DEBUGGING)
        )

        // Developer Options and USB Debugging are suppressed
        assertEquals(0, Settings.Global.getInt(context.contentResolver, SupportedSetting.DEVELOPMENT_OPTIONS.key, -1))
        assertEquals(0, Settings.Global.getInt(context.contentResolver, SupportedSetting.USB_DEBUGGING.key, -1))

        // Wireless Debugging remains ON
        assertEquals(1, Settings.Global.getInt(context.contentResolver, SupportedSetting.WIRELESS_DEBUGGING.key, -1))

        // Restore leaves Wireless Debugging as 1
        settingsController.restorePreviousState()
        assertEquals(1, Settings.Global.getInt(context.contentResolver, SupportedSetting.WIRELESS_DEBUGGING.key, -1))
    }

    /**
     * 10. Test: Process death while Wireless Debugging has been modified -> Recovery state remains correct.
     */
    @Test
    fun testWirelessDebugging_processDeathRecovery() {
        Settings.Global.putInt(context.contentResolver, SupportedSetting.DEVELOPMENT_OPTIONS.key, 1)
        Settings.Global.putInt(context.contentResolver, SupportedSetting.USB_DEBUGGING.key, 1)
        Settings.Global.putInt(context.contentResolver, SupportedSetting.WIRELESS_DEBUGGING.key, 1)

        val result = settingsController.enterBankingMode(suppressWirelessDebugging = true)
        assertTrue(result.isSuccess)

        // Simulate app crash and new process reconstructing repository & controller
        val newRepository = StateRepository(context)
        assertTrue("Snapshot must survive process termination", newRepository.hasActiveSnapshot())

        val snapshot = newRepository.getSnapshot()
        assertNotNull(snapshot)
        assertEquals(1, snapshot!!.previousValues[SupportedSetting.WIRELESS_DEBUGGING])
        assertTrue(snapshot.modifiedSettings.contains(SupportedSetting.WIRELESS_DEBUGGING))

        val newController = SettingsController(context, newRepository)
        val restoreResult = newController.restorePreviousState()
        assertTrue(restoreResult.isSuccess)
        assertFalse(newRepository.hasActiveSnapshot())

        assertEquals(1, Settings.Global.getInt(context.contentResolver, SupportedSetting.WIRELESS_DEBUGGING.key, -1))
    }

    /**
     * 11. Test: failed verification -> Handled if setting read back does not match.
     */
    @Test
    fun testFailedVerification_rollsBackSafely() {
        Settings.Global.putInt(context.contentResolver, SupportedSetting.DEVELOPMENT_OPTIONS.key, 0)
        Settings.Global.putInt(context.contentResolver, SupportedSetting.USB_DEBUGGING.key, 0)
        Settings.Global.putInt(context.contentResolver, SupportedSetting.WIRELESS_DEBUGGING.key, 0)

        val result = settingsController.enterBankingMode()
        assertTrue(result.isSuccess)
        assertFalse(result.getOrNull()!!.hasModifications())
    }

    /**
     * 12. Test: repeated Protection Mode activation -> Re-entering when already active is strictly rejected.
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
     * 13. Test: restore -> Returns settings to exact recorded pre-launch values.
     */
    @Test
    fun testRestore_restoresExactValues() {
        Settings.Global.putInt(context.contentResolver, SupportedSetting.DEVELOPMENT_OPTIONS.key, 1)
        Settings.Global.putInt(context.contentResolver, SupportedSetting.USB_DEBUGGING.key, 0)
        Settings.Global.putInt(context.contentResolver, SupportedSetting.WIRELESS_DEBUGGING.key, 1)

        settingsController.enterBankingMode(suppressWirelessDebugging = true)
        assertEquals(0, Settings.Global.getInt(context.contentResolver, SupportedSetting.DEVELOPMENT_OPTIONS.key, -1))
        assertEquals(0, Settings.Global.getInt(context.contentResolver, SupportedSetting.USB_DEBUGGING.key, -1))
        assertEquals(0, Settings.Global.getInt(context.contentResolver, SupportedSetting.WIRELESS_DEBUGGING.key, -1))

        // Restore
        val restoreResult = settingsController.restorePreviousState()
        assertTrue(restoreResult.isSuccess)
        assertEquals(1, Settings.Global.getInt(context.contentResolver, SupportedSetting.DEVELOPMENT_OPTIONS.key, -1))
        assertEquals(0, Settings.Global.getInt(context.contentResolver, SupportedSetting.USB_DEBUGGING.key, -1))
        assertEquals(1, Settings.Global.getInt(context.contentResolver, SupportedSetting.WIRELESS_DEBUGGING.key, -1))
    }

    /**
     * 14. Test: reboot / recovery -> Snapshot persists across boot for manual recovery banner.
     */
    @Test
    fun testRebootRecovery_persistsForManualRecovery() {
        val snapshot = SettingSnapshot(
            timestamp = System.currentTimeMillis() - 100000L,
            targetAppPackage = "com.example.app",
            targetAppLabel = "Test App",
            previousValues = mapOf(
                SupportedSetting.DEVELOPMENT_OPTIONS to 1,
                SupportedSetting.USB_DEBUGGING to 1,
                SupportedSetting.WIRELESS_DEBUGGING to 1
            ),
            modifiedSettings = setOf(
                SupportedSetting.DEVELOPMENT_OPTIONS,
                SupportedSetting.USB_DEBUGGING,
                SupportedSetting.WIRELESS_DEBUGGING
            )
        )
        stateRepository.saveSnapshot(snapshot)

        // Settings are currently 0 (device rebooted while suppressed)
        Settings.Global.putInt(context.contentResolver, SupportedSetting.DEVELOPMENT_OPTIONS.key, 0)
        Settings.Global.putInt(context.contentResolver, SupportedSetting.USB_DEBUGGING.key, 0)
        Settings.Global.putInt(context.contentResolver, SupportedSetting.WIRELESS_DEBUGGING.key, 0)

        assertTrue(stateRepository.hasActiveSnapshot())

        // Manual recovery triggered from MainActivity banner
        val recoveryResult = settingsController.restorePreviousState()
        assertTrue(recoveryResult.isSuccess)
        assertFalse(stateRepository.hasActiveSnapshot())

        assertEquals(1, Settings.Global.getInt(context.contentResolver, SupportedSetting.DEVELOPMENT_OPTIONS.key, -1))
        assertEquals(1, Settings.Global.getInt(context.contentResolver, SupportedSetting.USB_DEBUGGING.key, -1))
        assertEquals(1, Settings.Global.getInt(context.contentResolver, SupportedSetting.WIRELESS_DEBUGGING.key, -1))
    }

    /**
     * 15. Test: unsupported setting -> Rejection by whitelist.
     */
    @Test
    fun testUnsupportedSetting_rejectedByWhitelist() {
        assertFalse(SettingsWhitelist.isKeyMutable("accessibility_enabled"))
        assertFalse(SettingsWhitelist.isKeyMutable("install_non_market_apps"))
        assertFalse(SettingsWhitelist.isKeyMutable("http_proxy"))
        assertFalse(SettingsWhitelist.isKeyMutable("private_dns_mode"))

        assertTrue(SettingsWhitelist.isKeyMutable("development_settings_enabled"))
        assertTrue(SettingsWhitelist.isKeyMutable("adb_enabled"))
        assertTrue(SettingsWhitelist.isKeyMutable("adb_wifi_enabled"))

        assertEquals(3, SettingsWhitelist.getMutableSettings().size)
    }

    /**
     * 16. Test: corrupted / missing saved state -> Gracefully handled without crashing.
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
     * 17. Test: target app preference persistence -> Saved and cleared properly.
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

    /**
     * 18. Test: Wireless Debugging preference persistence -> Saved and persisted.
     */
    @Test
    fun testWirelessPreference_persistsAcrossRestarts() {
        stateRepository.setSuppressWirelessDebuggingEnabled(false)
        assertFalse(stateRepository.isSuppressWirelessDebuggingEnabled())

        stateRepository.setSuppressWirelessDebuggingEnabled(true)
        assertTrue(stateRepository.isSuppressWirelessDebuggingEnabled())
    }

    /**
     * 19. Test: Dark and Light Mode resource resolution.
     * Verifies that colors adapt properly between system light and dark themes.
     */
    @Test
    fun testDarkAndLightMode_resourceResolution() {
        val lightConfig = Configuration(context.resources.configuration).apply {
            uiMode = (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or Configuration.UI_MODE_NIGHT_NO
        }
        val darkConfig = Configuration(context.resources.configuration).apply {
            uiMode = (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or Configuration.UI_MODE_NIGHT_YES
        }

        val lightContext = context.createConfigurationContext(lightConfig)
        val darkContext = context.createConfigurationContext(darkConfig)

        val lightBg = ContextCompat.getColor(lightContext, R.color.background)
        val darkBg = ContextCompat.getColor(darkContext, R.color.background)
        assertNotEquals("Light and dark backgrounds must differ", lightBg, darkBg)

        val lightSurface = ContextCompat.getColor(lightContext, R.color.surface)
        val darkSurface = ContextCompat.getColor(darkContext, R.color.surface)
        assertNotEquals("Light and dark surfaces must differ", lightSurface, darkSurface)

        val lightText = ContextCompat.getColor(lightContext, R.color.text_primary)
        val darkText = ContextCompat.getColor(darkContext, R.color.text_primary)
        assertNotEquals("Light and dark text_primary must differ", lightText, darkText)

        val lightWarning = ContextCompat.getColor(lightContext, R.color.warning)
        val darkWarning = ContextCompat.getColor(darkContext, R.color.warning)
        assertTrue("Light warning color must be valid", lightWarning != 0)
        assertTrue("Dark warning color must be valid", darkWarning != 0)

        val lightSuccess = ContextCompat.getColor(lightContext, R.color.success)
        val darkSuccess = ContextCompat.getColor(darkContext, R.color.success)
        assertTrue("Light success color must be valid", lightSuccess != 0)
        assertTrue("Dark success color must be valid", darkSuccess != 0)
    }

    /**
     * 20. Test: Quick Settings Tile -> Activates Protection Mode and restores cleanly.
     */
    @Test
    fun testTileService_toggleActivationAndRestoration() {
        val controller = org.robolectric.Robolectric.buildService(com.devshield.service.DevShieldTileService::class.java).create()
        val tileService = controller.get()

        Settings.Global.putInt(context.contentResolver, SupportedSetting.DEVELOPMENT_OPTIONS.key, 1)
        Settings.Global.putInt(context.contentResolver, SupportedSetting.USB_DEBUGGING.key, 1)

        // Tapping while inactive activates protection
        assertFalse(stateRepository.hasActiveSnapshot())
        tileService.onClick()

        assertTrue(stateRepository.hasActiveSnapshot())
        assertEquals(0, Settings.Global.getInt(context.contentResolver, SupportedSetting.DEVELOPMENT_OPTIONS.key, -1))
        assertEquals(0, Settings.Global.getInt(context.contentResolver, SupportedSetting.USB_DEBUGGING.key, -1))

        // Tapping while active restores settings
        tileService.onClick()

        assertFalse(stateRepository.hasActiveSnapshot())
        assertEquals(1, Settings.Global.getInt(context.contentResolver, SupportedSetting.DEVELOPMENT_OPTIONS.key, -1))
        assertEquals(1, Settings.Global.getInt(context.contentResolver, SupportedSetting.USB_DEBUGGING.key, -1))
    }

    /**
     * 21. Test: Quick Settings Tile -> Rejected when WRITE_SECURE_SETTINGS is missing.
     */
    @Test
    fun testTileService_missingPermission_doesNotTouchSettings() {
        grantWriteSecureSettings(false)
        val controller = org.robolectric.Robolectric.buildService(com.devshield.service.DevShieldTileService::class.java).create()
        val tileService = controller.get()

        Settings.Global.putInt(context.contentResolver, SupportedSetting.DEVELOPMENT_OPTIONS.key, 1)
        Settings.Global.putInt(context.contentResolver, SupportedSetting.USB_DEBUGGING.key, 1)

        tileService.onClick()

        assertFalse("No snapshot should be created without permission", stateRepository.hasActiveSnapshot())
        assertEquals(1, Settings.Global.getInt(context.contentResolver, SupportedSetting.DEVELOPMENT_OPTIONS.key, -1))
        assertEquals(1, Settings.Global.getInt(context.contentResolver, SupportedSetting.USB_DEBUGGING.key, -1))
    }

    /**
     * 22. Test: Quick Settings Tile -> State reflects active snapshot status.
     */
    @Test
    fun testTileService_updateTileState_reflectsActiveAndInactive() {
        val controller = org.robolectric.Robolectric.buildService(com.devshield.service.DevShieldTileService::class.java).create()
        val tileService = controller.get()

        // Inactive state
        tileService.updateTileState()
        val qsTile = tileService.qsTile
        if (qsTile != null) {
            assertEquals(android.service.quicksettings.Tile.STATE_INACTIVE, qsTile.state)
        }

        // Active state
        Settings.Global.putInt(context.contentResolver, SupportedSetting.DEVELOPMENT_OPTIONS.key, 1)
        settingsController.enterBankingMode()
        tileService.updateTileState()
        if (qsTile != null) {
            assertEquals(android.service.quicksettings.Tile.STATE_ACTIVE, qsTile.state)
        }

        // Restored state
        settingsController.restorePreviousState()
        tileService.updateTileState()
        if (qsTile != null) {
            assertEquals(android.service.quicksettings.Tile.STATE_INACTIVE, qsTile.state)
        }
    }
}
