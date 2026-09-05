# DevShield 🛡️

[![Build & Test](https://github.com/placeholder/devshield/actions/workflows/build.yml/badge.svg)](https://github.com/placeholder/devshield/actions/workflows/build.yml)
[![Version](https://img.shields.io/badge/Version-1.2.0-blue.svg)](https://github.com/placeholder/devshield/releases)
[![Platform](https://img.shields.io/badge/Platform-Android%208.0%2B%20(API%2026%E2%80%9336)-3DDC84.svg?logo=android)](https://developer.android.com)
[![Target SDK](https://img.shields.io/badge/Target%20SDK-36%20(Android%2016)-blue.svg)](https://developer.android.com)
[![Root Required](https://img.shields.io/badge/Root-Not%20Required-success.svg)]()
[![Privacy](https://img.shields.io/badge/Network%20Access-Offline%20Only-brightgreen.svg)]()

**DevShield** is an offline, security-focused Android utility that enables Android developers, power users, and engineers to temporarily disable Android developer/debugging settings so applications that reject debugging-enabled devices can run smoothly—without permanently sacrificing your developer environment.

While financial, payment, and banking applications represent the most common use cases, DevShield is fundamentally a general-purpose protection tool for any app that blocks execution when debugging flags are detected. With an atomic, verified **Protection Mode**, DevShield snapshots your active settings, temporarily disables them, launches your chosen app, and provides a 1-tap restore action right from your notification shade or in-app banner.

---

## 🌟 Key Features

* **🛡️ One-Tap Protection**: Automatically snapshots your debugging settings, suppresses `development_settings_enabled`, `adb_enabled`, and (optionally) `adb_wifi_enabled`, verifies read-backs directly with Android's `SettingsProvider`, and optionally launches your target application.
* **📶 Wireless Debugging Protection (v1.2.0)**: User-controllable toggle to suppress Wireless Debugging alongside USB debugging and developer options. Includes automatic value snapshotting, verified suppression, idempotent no-op when already disabled, atomic rollback, and complete restoration.
* **📱 Target App Quick Selector**: Select your target application once. DevShield remembers your choice across launches, renders the app's real icon and name, and offers a single "Shield & Launch" action. (Selection is fully optional; toggle-only mode is supported).
* **📌 Fixed Modern Header**: Android 16 / Material 3 edge-to-edge layout featuring a pinned top header that stays visible while scrolling, respecting hardware camera cutouts and system window insets without clipping or overlap.
* **🌓 System Adaptive Dark & Light Themes**: Deliberately styled themes that follow system settings with high contrast and Material Design 3 elevation.
* **🔔 Notification Shade Restore Action**: An ongoing, low-priority status notification gives you instant 1-tap restoration from anywhere on your device without reopening the app.
* **⚡ Rock-Solid Durability & Crash Recovery**: State snapshots are committed to private storage *before* any system setting is touched. Even if DevShield's process is killed or the phone reboots, the un-restored state is safely detected upon next launch with an emergency restore banner.
* **🔒 Strict Security Architecture**:
  * **100% Offline**: Zero `INTERNET` or `ACCESS_NETWORK_STATE` permissions in `AndroidManifest.xml`. Telemetry and network exfiltration are technically impossible.
  * **Compile-Time Whitelist Boundary**: Only `DEVELOPMENT_OPTIONS`, `USB_DEBUGGING`, and `WIRELESS_DEBUGGING` are mutable. Arbitrary settings writes are rejected at the code boundary with a `SecurityException`.
  * **No Root / No Shizuku**: Operates using standard Android `WRITE_SECURE_SETTINGS` granted once via ADB.
  * **No Accessibility Services**: DevShield does not use accessibility services, eliminating any risk of keystroke interception or tapjacking.

---

## 🚀 Quick Start Guide

### 1. Install DevShield
Download the latest APK (`DevShield-v1.2.0.apk`) from the [Releases](https://github.com/placeholder/devshield/releases) page or build it locally using Gradle.

```bash
adb install DevShield-debug.apk
```

### 2. Grant Privileged Permission (One-Time Setup)
Because DevShield does not require root, it uses Android's native `WRITE_SECURE_SETTINGS` permission. By design in Android, `WRITE_SECURE_SETTINGS` has a protection level of `signature|privileged|development`. Applications cannot grant this permission to themselves or prompt the user for it with a standard runtime dialog. It must be explicitly granted once from a computer via ADB:

```bash
adb shell pm grant com.devshield android.permission.WRITE_SECURE_SETTINGS
```

*(On Android 13+, DevShield will also request runtime Notification permission so it can post the persistent Restore action to the notification shade).*

### 3. Use Protection Mode
1. Open **DevShield**.
2. Optionally configure **Suppress Wireless Debugging** (enabled by default).
3. Tap **Select App** and pick your target app from the list (optional; you can also toggle without selecting an app).
4. Tap **Shield & Launch** (or **Enter Protection Mode**).
5. Use your application.
6. When finished, pull down the notification shade and tap **Restore Settings** (or tap **Restore Developer Settings** inside DevShield) to immediately re-enable your developer environment.

> [!NOTE]
> **Physical USB Cable Disconnection**:
> Some applications check whether a physical USB connection exists by querying `BatteryManager.BATTERY_PLUGGED_USB` or kernel USB sysfs nodes. If an application still detects a computer connection after developer options are turned off, simply disconnect the physical USB cable from your device.

---

## 📡 Wireless Debugging & Shizuku Compatibility

Starting in **v1.2.0**, DevShield provides native suppression for **Wireless Debugging** (`adb_wifi_enabled`).

### User Preference Toggle
Inside the Protection Mode card, users can toggle **Suppress Wireless Debugging** (default: **ON**):
* **When ON**: `adb_wifi_enabled` is snapshotted and suppressed to `0` whenever Protection Mode is activated, and restored to its original value upon restoration.
* **When OFF**: Wireless Debugging is left untouched, allowing wireless ADB sessions to remain connected while Developer Options and USB Debugging are suppressed.

> [!WARNING]
> **Shizuku and Wireless ADB Services Notice**:
> Disabling Wireless Debugging may disconnect wireless ADB services such as Shizuku. If a service stops, start it again after restoring Wireless Debugging.
>
> In AOSP (`AdbDebuggingManager`), toggling `adb_wifi_enabled` from `0` back to `1` causes the daemon to rebind to a new ephemeral TCP port. If you use Shizuku via wireless debugging, you will need to re-link or tap "Start" in Shizuku once Wireless Debugging is restored.

---

## 📐 Architecture & Security Design

```
+-------------------------------------------------------------+
|                         DevShield                           |
|                                                             |
|  [Fixed Header UI]  <--->  [SettingsController]             |
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
5. **Verified Restoration**: Upon user tap of "Restore Developer Settings", pre-launch values are written back, verified via read-back, and only then is the persistent snapshot cleared.

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
DevShield includes 19 comprehensive Robolectric unit tests validating permission checks, verified writes, rollbacks, crash durability, reboot recovery, compile-time whitelist rejection, Wireless Debugging suppression and restoration, preference toggle behavior, and target app preference persistence:

```bash
./gradlew testDebugUnitTest
```

---

## 📱 Hardware & OS Compatibility

DevShield has been physically tested and verified on:
* **OnePlus Devices (OnePlus 12 / 12R / Open / Nord)** running **OxygenOS 16 / Android 16 (API 36)**
* Generic Android 8.0 (API 26) through Android 16 (API 36)

---

## 📄 License
DevShield is open-source software licensed under the [Apache License 2.0](LICENSE).
