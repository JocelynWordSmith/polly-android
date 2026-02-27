# Android — AGENTS.md

## What This Is

Kotlin Android app that runs on a Google Pixel 3 mounted on the robot. It bridges hardware (Arduino via USB serial, cameras, phone sensors, FLIR thermal) and exposes everything over a WebSocket server on port 8080.

## Tech Stack

- Kotlin 2.1.0 + Jetpack Compose
- Gradle 8.13.2
- Min SDK 26 (Android 8.0), Target SDK 34
- CameraX, NanoHTTPD, usb-serial-for-android
- FLIR One SDK (AAR in `app/libs/`)

## Key Files

| File | Purpose | When to read |
|------|---------|-------------|
| `app/src/main/java/com/robotics/polly/MainActivity.kt` | App entry point | Always |
| `app/src/main/java/com/robotics/polly/BridgeService.kt` | Background service managing all bridges | Architecture |
| `app/src/main/java/com/robotics/polly/ArduinoBridge.kt` | USB serial communication to Arduino | Serial protocol |
| `app/src/main/java/com/robotics/polly/PollyWebSocketServer.kt` | WebSocket server (port 8080) | WebSocket API |
| `app/src/main/java/com/robotics/polly/UsbSerialManager.kt` | USB connection management + auto-reconnect | USB issues |
| `app/src/main/java/com/robotics/polly/CameraBridge.kt` | Phone camera via CameraX | Camera stream |
| `app/src/main/java/com/robotics/polly/FlirBridge.kt` | FLIR One Gen 3 thermal camera | Thermal imaging |
| `app/src/main/java/com/robotics/polly/ImuBridge.kt` | Phone IMU sensors | IMU data |
| `app/src/main/java/com/robotics/polly/FirmwareUploader.kt` | OTA firmware upload orchestrator | Firmware upload |
| `app/src/main/java/com/robotics/polly/Stk500Programmer.kt` | STK500v1 bootloader protocol | Firmware upload |
| `app/src/main/java/com/robotics/polly/IntelHexParser.kt` | Intel HEX file parser | Firmware upload |
| `app/src/main/AndroidManifest.xml` | Permissions, USB device filters | Permissions |

## Build & Deploy

```bash
cd android
./gradlew assembleDebug     # Build APK
./build.sh                  # Same thing
./deploy.sh                 # Install and launch on device
./deploy-and-verify.sh      # Deploy + verify it started
```

Or from the repo root:
```bash
make android-build
```

**Requirements:** Android SDK (API 34), JDK 17+, ADB, connected Pixel 3.

## WebSocket Endpoints (port 8080)

The phone exposes these WebSocket endpoints. The server connects to each one independently.

| Endpoint | Data Type | Format |
|----------|-----------|--------|
| `/arduino` | Arduino sensor JSON (text) | `{"ts":..., "dist_f":18, ...}` |
| `/imu` | Phone IMU data (text) | `{"ax":0.1, "ay":9.8, ...}` |
| `/camera` | JPEG frames (binary) | Raw JPEG bytes |
| `/flir` | Thermal data (binary) | `[u16 width][u16 height][u32 min][u32 max][u16[] pixels]` |
| `/control` | Motor commands (text) | `{"target":"arduino", "cmd":"..."}` |
| `/firmware` | OTA firmware upload (text) | Send Intel HEX content; receive progress JSON |

## Sensors

**Phone sensors (11):** Accelerometer, Gyroscope, Magnetometer, GPS, Light, Barometer, Proximity, Temperature, Microphone, Camera, Step counter

**Arduino sensors (4, via USB):** HC-SR04 ultrasonic, IR line tracking, MPU-6050 IMU, Battery voltage

## Notes

- USB serial runs at 115200 baud, 5 Hz polling
- Auto-reconnects on USB disconnect (max 5 attempts)
- FLIR SDK is a binary AAR — source not available
- FLIR requires external USB device via hub
- OTA firmware upload: client sends Intel HEX over `/firmware` WebSocket, app parses it, resets Arduino into bootloader via DTR toggle, programs via STK500v1, then resumes normal operation
- During firmware upload, normal serial streaming and watchdog are paused automatically
