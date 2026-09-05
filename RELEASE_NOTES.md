# DevShield v1.3.0

DevShield v1.3.0 introduces an Android Quick Settings tile, expanded unit testing, and refined documentation while maintaining zero background daemons and strict offline privacy.

## Highlights
* **Quick Settings Tile**: Added an Android Quick Settings tile (`TileService`) enabling one-tap activation and restoration of Protection Mode directly from the notification shade. The tile integrates with the existing snapshot, allowlist, verification, and rollback logic without maintaining any persistent background services.
* **Synchronized State**: The Quick Settings tile automatically stays synchronized with in-app controls and notification restore actions.
* **Wireless Debugging Protection**: User-configurable suppression of Wireless Debugging (`adb_wifi_enabled`) alongside Developer Options and USB Debugging.
* **Shizuku / Wireless ADB Compatibility**: Includes explicit preference toggling to avoid disrupting wireless ADB sessions or Shizuku workflows.
* **Verified Suppression & Atomic Rollback**: Every setting modification is verified through immediate synchronous read-back. Any failure triggers an atomic rollback to pre-launch baseline values.
* **Comprehensive Test Suite**: Expanded to 22 comprehensive Robolectric unit tests covering Quick Settings tile activation, restoration, permission checking, Wireless Debugging toggle behavior, and process-death recovery.

## Recommended Installation
Download and install the single recommended APK attached to this release:

```bash
adb install DevShield-v1.3.0.apk
```

### Grant Privileged Permission (One-Time Setup)
Grant `WRITE_SECURE_SETTINGS` via ADB (no root required):
```bash
adb shell pm grant com.devshield android.permission.WRITE_SECURE_SETTINGS
```
*(On Android 13+, grant runtime notification permission when prompted to enable the persistent notification restore action).*
