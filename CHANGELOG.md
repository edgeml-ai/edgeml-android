# Changelog

## 1.1.0 - 2026-02-06
- Added `DeviceAuthManager` with Android Keystore-backed encrypted token storage.
- Added short-lived device token lifecycle support: bootstrap, refresh, revoke.
- Updated docs to use backend-issued short-lived device tokens.
- Corrected integration examples to pass `Context` explicitly.
- Removed restricted permission guidance (`BATTERY_STATS`).
