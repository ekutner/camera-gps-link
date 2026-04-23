# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

Camera GPS Link — an Android app that connects to Sony cameras via Bluetooth Low Energy (BLE) to sync GPS location, time, and provide remote control (shutter, zoom, focus, record). Supports multiple simultaneous camera connections.

## Build & Run

```bash
# Build debug APK
./gradlew assembleDebug

# Build release APK (requires signing config via keystore.properties or environment vars)
./gradlew assembleRelease

# Run unit tests
./gradlew test

# Run a single unit test class
./gradlew testDebugUnitTest --tests "org.kutner.cameragpslink.ExampleUnitTest"

# Run instrumented tests (requires connected device/emulator)
./gradlew connectedAndroidTest

# Clean build
./gradlew clean
```

## Build Configuration

- **Build system:** Gradle with Kotlin DSL + Version Catalog (`gradle/libs.versions.toml`)
- **compileSdk / targetSdk:** 36, **minSdk:** 26
- **Kotlin Compose plugin** enabled; UI is entirely Jetpack Compose with Material 3
- **Java/Kotlin target:** JVM 11
- **Proguard:** disabled for release builds
- **Signing:** Release keystore configured via `keystore.properties` (local) or environment variables `KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD` (CI)
- **CI:** GitHub Actions (`.github/workflows/android-build.yml`) — builds debug + release APKs on push/PR, uses JDK 17

## Architecture

Single-module app (`org.kutner.cameragpslink`). No dependency injection framework.

### Key files

- **CameraSyncService.kt** (~1400 lines) — Core foreground service: BLE scanning, GATT connections, location tracking (FusedLocationProviderClient), GPS/time sync, remote control commands. Exposes UI state via `StateFlow`. This is the largest and most critical file.
- **MainActivity.kt** — Compose UI host. Binds to `CameraSyncService` via `ServiceConnection`, handles permissions, deep links (`cameragpslink://remote`), in-app review prompts.
- **Consttants.kt** (note: filename has intentional double-t) — Sony camera BLE UUIDs, remote control command enums, camera status response codes, notification constants.
- **AppSettingsManager.kt** — SharedPreferences wrapper with LinkedHashMap cache for ordered camera settings. Uses Gson for JSON serialization.
- **NotificationHelper.kt** — Notification channels (High/Low/Error/Boot) and foreground service notification management.
- **LanguageManager.kt** — Runtime locale switching (EN, ES, FR, DE, HE, system default).
- **BootReceiver.kt** — BOOT_COMPLETED receiver; posts reminder notification.

### UI layer (`composables/`)

Compose dialogs and cards: `RemoteControlDialog`, `ConnectedCameraCard`, `FoundCameraCard`, `SearchDialog`, `CameraSettingsDialog`, `ReorderableCameraList`, `BondingErrorDialog`, `LanguageSelectionDialog`, `LogCard`.

### State flow

`CameraSyncService` manages all BLE and location state → exposes `StateFlow` → `MainActivity` collects and renders via Compose. No ViewModel layer; the service acts as the state holder.

## Bluetooth Protocol

Sony-specific BLE protocol. UUIDs and command definitions are in `Consttants.kt`. Additional protocol documentation in `sony-camera-bt-info.md`.

## Localization

String resources in `res/values/strings.xml` with translations: German (`-de`), Spanish (`-es`), French (`-fr`), Hebrew (`-iw`).
