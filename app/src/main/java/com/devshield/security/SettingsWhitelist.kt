package com.devshield.security

import com.devshield.model.SupportedSetting

/**
 * Security boundary that enforces strict compile-time whitelisting.
 *
 * It prevents arbitrary settings writes. Under no circumstances may an arbitrary
 * string key be passed to ContentResolver through this interface.
 */
object SettingsWhitelist {

    private val ALLOWED_MUTABLE_SETTINGS: Set<SupportedSetting> = setOf(
        SupportedSetting.DEVELOPMENT_OPTIONS,
        SupportedSetting.USB_DEBUGGING
    )

    private val ALLOWED_MUTABLE_KEYS: Set<String> = ALLOWED_MUTABLE_SETTINGS.map { it.key }.toSet()

    /**
     * Asserts that the given setting is explicitly permitted for mutation.
     * Throws [SecurityException] if an unauthorized setting is supplied.
     */
    fun assertMutable(setting: SupportedSetting) {
        if (setting !in ALLOWED_MUTABLE_SETTINGS) {
            throw SecurityException(
                "Setting '${setting.name}' (${setting.key}) is not permitted for modification by DevShield."
            )
        }
    }

    /**
     * Checks whether a raw key string is part of the approved mutable whitelist.
     */
    fun isKeyMutable(key: String): Boolean {
        return key in ALLOWED_MUTABLE_KEYS
    }

    /**
     * Returns the immutable set of settings permitted for suppression.
     */
    fun getMutableSettings(): Set<SupportedSetting> {
        return ALLOWED_MUTABLE_SETTINGS
    }
}
