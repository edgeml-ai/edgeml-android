# Changelog

## Unreleased

- Added `DeviceAuthManager` with Android Keystore-backed token storage for bootstrap/refresh/revoke flows.
- Added runtime auth documentation for short-lived device tokens.
## 1.2.0 (2026-02-26)

### Features

- add funnel event reporting to Android SDK (#88)
- add client-side training resilience (#89)
- migrate Android SDK to v2 OTLP envelope format

### Fixes

- use BuildConfig version in telemetry and add release automation (#90)
- replace PAT with GitHub App token for cross-repo dispatch (#91)
- update knope.toml to v0.22+ config format
- add all version files to knope and sync versions
- split git add and git commit into separate knope steps
