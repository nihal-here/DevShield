package com.devshield.model

/**
 * Encapsulates the runtime state of whitelisted settings captured immediately
 * before entering Banking Mode.
 *
 * @property timestamp Unix timestamp when the snapshot was taken.
 * @property targetAppPackage Package name of the app being launched (if any).
 * @property targetAppLabel User-friendly label of the app being launched (if any).
 * @property previousValues Exact integer values read from the system before any write.
 * @property modifiedSettings Set of settings whose values were actually mutated by DevShield.
 */
data class SettingSnapshot(
    val timestamp: Long,
    val targetAppPackage: String? = null,
    val targetAppLabel: String? = null,
    val previousValues: Map<SupportedSetting, Int>,
    val modifiedSettings: Set<SupportedSetting>
) {
    /**
     * Checks if this snapshot contains any settings that need to be restored.
     */
    fun hasModifications(): Boolean = modifiedSettings.isNotEmpty()
}
