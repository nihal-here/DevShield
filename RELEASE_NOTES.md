# DevShield v1.2.0

DevShield v1.2.0 introduces user-configurable Wireless Debugging (`adb_wifi_enabled`) protection, Shizuku compatibility controls, atomic rollback enhancements, and expanded test coverage.

## Highlights
- **Wireless Debugging Protection**: Added native snapshotting, verified suppression, and state restoration for Wireless Debugging (`adb_wifi_enabled`) alongside Developer Options and USB Debugging.
- **User-Configurable Suppression Toggle**: Introduced the "Suppress Wireless Debugging" setting inside the Protection Mode card (enabled by default). Users can keep Wireless Debugging active if their workflow requires it while still suppressing developer options and USB debugging.
- **Shizuku / Wireless ADB Warning Callout**: Added an explicit in-app warning informing users that disabling Wireless Debugging terminates active ADB sessions (such as Shizuku). Clarifies that pairing information is retained by Android and Shizuku can simply be started again once Wireless Debugging is restored.
- **Verified Suppression & Atomic Rollback**: Every setting modification is verified through an immediate synchronous read-back query to `Settings.Global`. Any write failure triggers an atomic rollback to pre-launch baseline values.
- **Expanded System Diagnostics**: Real-time diagnostic monitor reflects whether Wireless Debugging is being suppressed based on the active user preference and explains dynamic port invalidation.
- **Robust Test Coverage**: Expanded test suite to 19 Robolectric unit tests covering Wireless Debugging suppression, no-op handling when already disabled, exact baseline restoration, preference toggle persistence, process death recovery, and theme resolution.

## Recommended Installation
Download and install the signed APK attached to this release:

```bash
adb install DevShield-v1.2.0.apk
```

### Grant Privileged Permission (One-Time Setup)
Grant `WRITE_SECURE_SETTINGS` via ADB (no root required):
```bash
adb shell pm grant com.devshield android.permission.WRITE_SECURE_SETTINGS
```
*(On Android 13+, grant runtime notification permissions when prompted to enable the persistent notification restore action).*
