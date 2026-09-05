# DevShield 🛡️

[![Build & Test](https://github.com/placeholder/devshield/actions/workflows/build.yml/badge.svg)](https://github.com/placeholder/devshield/actions/workflows/build.yml)
[![Platform](https://img.shields.io/badge/Platform-Android%208.0%2B%20(API%2026%E2%80%9336)-3DDC84.svg?logo=android)](https://developer.android.com)
[![Target SDK](https://img.shields.io/badge/Target%20SDK-36%20(Android%2016)-blue.svg)](https://developer.android.com)
[![Root Required](https://img.shields.io/badge/Root-Not%20Required-success.svg)]()
[![Privacy](https://img.shields.io/badge/Network%20Access-Offline%20Only-brightgreen.svg)]()

**DevShield** is an offline, security-focused Android utility that enables Android developers, power users, and engineers to safely use banking and enterprise applications without permanently sacrificing their developer environment.

Many modern banking, payment, and enterprise applications detect and refuse to run if **Developer Options** or **USB Debugging** are enabled. DevShield solves this with an atomic, verified **Banking Mode**: it snapshots your active developer settings, temporarily disables them, launches your chosen banking app, and provides a 1-tap restore action right from your notification shade.

---

## 🌟 Key Features

* **🛡️ One-Tap Banking Protection**: Automatically snapshots your settings, turns off `development_settings_enabled` and `adb_enabled`, verifies read-backs directly with `SettingsProvider`, and launches your banking application.
* **📱 Target App Quick Selector**: Select your primary banking or fintech app once. DevShield persists your choice, renders the app's real icon and name, and offers a single "Shield & Launch" action.
* **🔔 Notification Shade Restore Action**: An ongoing, low-priority status notification gives you instant 1-tap restoration from anywhere on your device without reopening the app.
* **⚡ Rock-Solid Durability & Crash Recovery**: State snapshots are committed to private storage *before* any system setting is touched. Even if DevShield's process is killed or the phone reboots, the un-restored state is safely detected upon next launch with an emergency restore banner.
* **🔒 Strict Security Architecture**:
  * **100% Offline**: Zero `INTERNET` or `ACCESS_NETWORK_STATE` permissions in `AndroidManifest.xml`. Telemetry and network exfiltration are technically impossible.
  * **Compile-Time Whitelist Boundary**: Only `DEVELOPMENT_OPTIONS` and `USB_DEBUGGING` are mutable. Arbitrary settings writes are rejected at the code boundary with a `SecurityException`.
  * **No Root / No Shizuku**: Operates using standard Android `WRITE_SECURE_SETTINGS` granted once via ADB.
  * **No Accessibility Services**: DevShield does not use accessibility services, eliminating any risk of keystroke interception or tapjacking.

---

## 🚀 Quick Start Guide

### 1. Install DevShield
Download the latest APK from the [Releases](https://github.com/placeholder/devshield/releases) page or build it locally using Gradle.

```bash
adb install DevShield-debug.apk
```

### 2. Grant Privileged Permission (One-Time Setup)
Because DevShield does not require root, it uses Android's native `WRITE_SECURE_SETTINGS` permission. Connect your phone to your computer with USB Debugging enabled and run:

```bash
adb shell pm grant com.devshield android.permission.WRITE_SECURE_SETTINGS
```

*(On Android 13+, DevShield will also request runtime Notification permission so it can post the persistent Restore button).*

### 3. Use Banking Mode
1. Open **DevShield**.
2. Tap **Select App** and pick your banking or financial app from the list (optional; you can also toggle without selecting an app).
3. Tap **Shield & Launch**.
4. Complete your banking transactions.
5. When finished, pull down the notification shade and tap **Restore Settings** (or tap **Restore** inside DevShield) to immediately re-enable your developer environment.

> [!NOTE]
> **Physical USB Cable Disconnection**:
> Some banking applications inspect battery charging state (`BatteryManager.BATTERY_PLUGGED_USB`) or kernel USB sysfs nodes. If an aggressive app still complains after developer options are turned off, simply disconnect the physical USB cable from your phone while using the app.

---

## 📐 Architecture & Security Design

```
+-------------------------------------------------------------+
|                         DevShield                           |
|                                                             |
|  [MainActivity UI]  <--->  [SettingsController]             |
|                                    |                        |
|                                    v                        |
|                       [SettingsWhitelist Boundary]          |
|                                    |                        |
|             +----------------------+----------------------+  |
|             |                                             |  |
|             v                                             v  |
|     [StateRepository]                             [ContentResolver] |
|    (Atomic Snapshot)                           (Verified Settings Read/Write)|
|             |                                             |  |
|             +----------------------+----------------------+  |
|                                    |                        |
|  [RestoreReceiver] <---------------+                        |
|  (Notification Action)                                      |
+-------------------------------------------------------------+
```

### Protocol Flow
1. **Permission Check**: Verifies `WRITE_SECURE_SETTINGS` is granted.
2. **Snapshot Before Write**: Reads current integer values of whitelisted settings (`development_settings_enabled`, `adb_enabled`). Writes `SettingSnapshot` to private `SharedPreferences` with `commit()`.
3. **Verified Suppression**: Writes `0` to each setting that was non-zero. Immediately queries `Settings.Global.getInt()` to verify each write succeeded.
4. **Atomic Rollback**: If any write or verification fails, all previously changed settings in that session are rolled back to their recorded pre-launch values.
5. **Verified Restoration**: Upon user tap of "Restore Settings", pre-launch values are written back, verified via read-back, and only then is the persistent snapshot cleared.

---

## 🛠️ Building & Testing

### Prerequisites
* JDK 17 or JDK 21
* Android SDK with Platform 36 and Build-Tools 35.0.0+

### Build APK
```bash
# Debug APK
./gradlew assembleDebug

# Release APK
./gradlew assembleRelease
```
Outputs:
* `app/build/outputs/apk/debug/app-debug.apk`
* `app/build/outputs/apk/release/app-release-unsigned.apk`

### Run Unit Tests
DevShield includes 13 Robolectric unit tests validating permission checks, verified writes, rollbacks, crash durability, reboot recovery, whitelist rejection, and target app preference persistence:

```bash
./gradlew testDebugUnitTest
```

---

## 📱 Hardware & OS Compatibility

DevShield has been physically tested and verified on:
* **OnePlus 12 / Open / 11 / Nord** running **OxygenOS 16 / Android 16 (API 36)**
* Generic Android 8.0 (API 26) through Android 16 (API 36)

---

## 📄 License
DevShield is open-source software licensed under the [Apache License 2.0](LICENSE).
