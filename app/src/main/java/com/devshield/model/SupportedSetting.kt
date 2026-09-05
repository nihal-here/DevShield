package com.devshield.model

/**
 * Compile-time enumeration of system settings that DevShield recognizes.
 *
 * DevShield does NOT support arbitrary setting manipulation. Only settings
 * explicitly declared in this enum can be accessed.
 */
enum class SettingNamespace {
    GLOBAL,
    SECURE
}

enum class SupportedSetting(
    val key: String,
    val namespace: SettingNamespace,
    val label: String,
    val isSuppressible: Boolean
) {
    DEVELOPMENT_OPTIONS(
        key = "development_settings_enabled",
        namespace = SettingNamespace.GLOBAL,
        label = "Developer Options",
        isSuppressible = true
    ),
    USB_DEBUGGING(
        key = "adb_enabled",
        namespace = SettingNamespace.GLOBAL,
        label = "USB Debugging",
        isSuppressible = true
    ),
    WIRELESS_DEBUGGING(
        key = "adb_wifi_enabled",
        namespace = SettingNamespace.GLOBAL,
        label = "Wireless Debugging",
        isSuppressible = true
    );

    companion object {
        /**
         * Returns only settings that are approved for suppression in Banking Mode.
         */
        val suppressibleSettings: List<SupportedSetting> by lazy {
            entries.filter { it.isSuppressible }
        }

        fun fromKey(key: String): SupportedSetting? {
            return entries.find { it.key == key }
        }
    }
}
