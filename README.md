# OmniView RTSP

An open-source Android and Android TV multi-camera **RTSP/ONVIF surveillance NVR**, **local/network clip recorder**, and **self-hosted Remote Hub server** built with Kotlin, Jetpack Compose, and Media3 ExoPlayer.

---

## Features

- **Multi-Camera Live Matrix**
  - Simultaneous RTSP playback (`TCP` / `UDP`) powered by Media3 `ExoPlayer` with automatic stream watchdog & reconnect recovery, hardware/software decoder fallback, and live FPS/bitrate/resolution telemetry.
  - Switchable `1x1` / `2x2` grid, drag-to-resize individual feeds, per-camera free rotation (`0°–360°`), synced global audio mute, Picture-in-Picture (PiP), and floating overlay windows (in-app or system-wide over other apps).
  - Responsive tablet & Android TV layout with `NavigationRail` and D-pad/TV mode support.
- **Auto-Discovery & ONVIF PTZ Control**
  - Dual-wave ONVIF WS-Discovery (`239.255.255.250:3702` + subnet broadcast) combined with high-concurrency LAN port scanning (`554`, `8554`, `10554`, `8000`, `2020`, `8899`, `80`, `8080`).
  - Real ONVIF SOAP `GetProfiles`, `GetNodes`, `GetStreamUri`, `GetPresets`, `ContinuousMove`, `RelativeMove`, and `Stop` with WS-Security (`PasswordDigest` / `PasswordText`) and HTTP Digest support.
- **Hardware-Accelerated MP4 Recording & Resilient Chunking**
  - Records live RTSP streams via `MediaCodec` + dedicated OpenGL ES 2.0 / EGL14 input surfaces (`HeadlessGlFrameGrabber` & `EncoderGlInputSurface`) with stride/sliceHeight-aligned YUV420 fallback.
  - Crash-resilient time-chunked recording (`1m` – `24h`), scheduled weekly recording windows, and motion-triggered recording (`Manual`, `Scheduled`, `Motion Alert`).
- **Continuous Timeline Playback**
  - Chronological multi-chunk playback (`StorageTimelinePlayer`) with interactive canvas scrubber, date/camera/trigger filters, previous/next motion-event jumps, `0.5x–8x` playback speed, fit/crop aspect toggle, and true edge-to-edge fullscreen controls.
- **Flexible Storage, Rolling Retention & Cloud/NAS Sync**
  - Saves clips locally to `MediaStore` (`Movies/OmniView`) or any user-selected Storage Access Framework (SAF) directory.
  - Configurable rolling auto-retention (`StorageRetentionEngine`) to prune old clips automatically.
  - Built-in uploaders for **SMB/CIFS**, **WebDAV**, **AWS S3 / MinIO**, **FTP / SFTP / HTTP Gateway**, and **Google Drive**.
- **Built-in Remote Camera Hub Server**
  - Turn a spare Android device into a headless or always-on local NVR hub accessible over **Tailscale**, **WireGuard**, **ZeroTier**, **LAN**, or **Hotspot**.
  - Secured with salted **PBKDF2-HMAC-SHA256** login + `HttpOnly` sessions, optional SHA-256 revocable API tokens, network-scope filtering (`VPN Only` / `LAN + VPN` / `All`), HTTP `Range` seeking for clips, live JPEG/MJPEG feeds, and remote PTZ/recording triggers.
- **Background Protection & Safe Shutdown**
  - Persistent foreground keep-alive service (`SurveillanceKeepAliveService`) with wake/Wi-Fi locks and live recording notification status.
  - Dedicated in-app **Close App Completely** control with an active-recording confirmation prompt that finalizes/flushes open MP4 writers before stopping services.
  - Live **English** & **Turkish** (`EN` / `TR`) localization switching.

---

## Tech Stack

| Layer | Technologies |
| :--- | :--- |
| **Language & UI** | Kotlin `2.2.10`, Jetpack Compose (Material 3), Edge-to-Edge |
| **Video & Graphics** | AndroidX Media3 (`1.5.1`) ExoPlayer + RTSP, OpenGL ES 2.0 / EGL14, `MediaCodec`, `MediaMuxer` |
| **Architecture & State** | MVVM, `StateFlow`, Kotlin Coroutines |
| **Local Database** | Room (`2.7.0`) with KSP (`2.3.6`) |
| **Networking** | OkHttp, Retrofit, Moshi, Custom Lightweight HTTP/ONVIF/SMB/FTP Clients |
| **Testing** | JUnit 4, Robolectric (`4.16.1`), Compose UI Test (`ui-test-junit4`) |

---

## Requirements

- **Android Studio**: Ladybug / Meerkat or newer supporting AGP `9.1.1`
- **JDK**: Java `21` (Temurin recommended)
- **Android SDK**: `compileSdk 36.1` (`platforms;android-36.1`), `minSdk 24`, `targetSdk 36`
- **Gradle**: `9.3.1` (bundled via `./gradlew`)

---

## Building Locally

### 1. Clone the repository

```bash
git clone https://github.com/<your-org>/omniview-rtsp.git
cd omniview-rtsp
```

### 2. Initialize local environment & debug keystore

The repository intentionally ignores `.env` and `debug.keystore` in `.gitignore`. Generate them from the included templates before building:

```bash
cp .env.example .env
base64 -d debug.keystore.base64 > debug.keystore
```

*(If `debug.keystore.base64` is absent, generate a standard debug keystore with `keytool`:)*

```bash
keytool -genkey -v \
  -keystore debug.keystore \
  -storepass android \
  -alias androiddebugkey \
  -keypass android \
  -keyalg RSA \
  -keysize 2048 \
  -validity 10000 \
  -dname "CN=Android Debug,O=Android,C=US"
```

> **Note on Firebase / `google-services.json`:** `googleServices.missing.passthrough=true` is enabled in `gradle.properties`, and `MissingGoogleServicesStrategy.WARN` is configured in `app/build.gradle.kts`. You do **not** need `google-services.json` to compile, test, or run RTSP/ONVIF/Hub features locally.

### 3. Run tests, lint, and build APK

```bash
chmod +x gradlew
./gradlew testDebugUnitTest
./gradlew :app:lintDebug
./gradlew assembleDebug
```

Debug APKs are output to:
`app/build/outputs/apk/debug/app-debug.apk`

---

## GitHub Actions CI/CD

Two workflows are included under `.github/workflows/`:

1. **`build.yml` (`Android CI Build`)** — Runs on pushes and PRs to `main`/`master` (plus manual `workflow_dispatch`):
   - Installs JDK 21 & Android SDK (`platforms;android-36.1`, `build-tools;36.0.0`).
   - Sets up `.env` and `debug.keystore`.
   - Runs `./gradlew testDebugUnitTest`, `./gradlew :app:lintDebug`, and `./gradlew assembleDebug`.
   - Uploads test reports and the debug APK artifact.
2. **`release.yml` (`Production Release Build`)** — Runs on `v*` tags (e.g. `v1.0.0`) or manual dispatch:
   - Runs `./gradlew testDebugUnitTest` and `./gradlew assembleRelease bundleRelease`.
   - Uploads release APK/AAB artifacts and creates a GitHub Release when triggered from a tag.

### Optional Release Signing Secrets

To sign release builds with your own upload keystore in GitHub Actions, configure these repository secrets under **Settings → Secrets and variables → Actions**:

| Secret Name | Description |
| :--- | :--- |
| `RELEASE_KEYSTORE_BASE64` | Base64-encoded `.jks` keystore (**must use key alias `upload`**) |
| `RELEASE_STORE_PASSWORD` | Keystore password |
| `RELEASE_KEY_PASSWORD` | Key password for alias `upload` |

If `RELEASE_KEYSTORE_BASE64` is not set, `release.yml` automatically generates an ephemeral keystore so open-source forks and test releases still build cleanly.

---

## Project Structure

```text
app/src/main/java/com/example/
├── MainActivity.kt                  # Edge-to-edge host, PiP, NavigationRail/BottomBar, runtime permissions
├── data/                            # Room database, DAOs, and entity models
├── engine/
│   ├── RtspStreamManager.kt         # ExoPlayer lifecycle, decoder fallback & stall watchdog
│   ├── CameraDiscoveryEngine.kt     # ONVIF WS-Discovery & LAN subnet scanner
│   ├── OnvifPtzClient.kt            # ONVIF SOAP PTZ & media stream URI resolver
│   ├── HeadlessGlFrameGrabber.kt    # Per-camera GLES/EGL frame grabber
│   ├── CameraStreamRecorder.kt      # MediaCodec H.264 + EGL14 MP4 muxer & chunk writer
│   ├── RecordingSchedulerEngine.kt  # Scheduled/manual/motion chunked recording coordinator
│   ├── MotionDetectionEngine.kt     # Frame luminance delta detector & alerts
│   ├── StorageTimelineEngine.kt     # Chronological segment stitcher & seek resolver
│   ├── StorageRetentionEngine.kt    # Rolling clip expiration & pruning
│   ├── DeviceStorageExporter.kt     # MediaStore & SAF exporter
│   ├── RemoteStorageEngine.kt       # SMB/FTP/SFTP/HTTP storage client
│   ├── CloudStorageManager.kt       # S3/WebDAV/Google Drive/NAS sync orchestrator
│   └── RemoteHubServer.kt           # Embedded HTTP NVR server with PBKDF2 auth & Range streaming
├── i18n/                            # Live runtime EN/TR locale manager
├── service/                         # Floating overlay & foreground keep-alive services
└── ui/                              # Jetpack Compose screens, components, theme, and ViewModel
```

---

## Permissions

| Permission | Purpose |
| :--- | :--- |
| `INTERNET`, `ACCESS_NETWORK_STATE`, `ACCESS_WIFI_STATE` | RTSP streaming, ONVIF PTZ control, Remote Hub server, and cloud/NAS sync |
| `CHANGE_WIFI_MULTICAST_STATE` | UDP multicast packets for ONVIF WS-Discovery (`239.255.255.250:3702`) |
| `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_MEDIA_PLAYBACK`, `WAKE_LOCK` | Reliable background live monitoring & chunked recording |
| `POST_NOTIFICATIONS` | Persistent monitoring status & motion alert notifications (Android 13+) |
| `SYSTEM_ALERT_WINDOW` | Optional floating camera windows over other apps |
| `VIBRATE` | Haptic/alert notification support |

---

## Security Notes

- Remote Hub credentials are stored as salted **PBKDF2-HMAC-SHA256** hashes; automation API keys are stored only as **SHA-256** hashes.
- Do not commit `.env`, `debug.keystore`, `*.jks`, or `app/google-services.json` to public repositories.
- When exposing the Remote Hub outside a trusted LAN, prefer **VPN Only** (`Tailscale` / `WireGuard` / `ZeroTier`) or place a TLS reverse proxy in front of the Hub port.

---

## Contributing

Contributions are welcome! Before opening a pull request:

1. Run the test suite: `./gradlew testDebugUnitTest`
2. Verify Android Lint passes with zero errors: `./gradlew :app:lintDebug`
3. Keep string resources synchronized across `app/src/main/res/values/strings.xml` and `app/src/main/res/values-tr/strings.xml`.

---

## License

This project is open source. Add a `LICENSE` file (such as `Apache-2.0` or `MIT`) in the repository root matching your preferred distribution terms before publishing.
