# Polly Robot Control

Native Android app for controlling an ELEGOO Smart Robot Car V4.0 using a Google Pixel 3 as the robot's brain.

![Status](https://img.shields.io/badge/status-operational-brightgreen)
![Platform](https://img.shields.io/badge/platform-Android%2012-blue)
![Language](https://img.shields.io/badge/language-Kotlin-purple)

## Features

### Hardware Integration
- **USB Serial Communication** - 5 Hz polling with auto-reconnection
- **15 Sensors Streaming** - 11 Pixel + 4 Arduino sensor categories
- **Motor Control** - Forward/backward/left/right with speed control (50-255)
- **Auto-Recovery** - Reconnects automatically on disconnect (max 5 attempts)

### Pixel 3 Sensors
- **Motion:** Accelerometer, Gyroscope, Magnetometer (compass)
- **Position:** GPS with altitude, speed, and accuracy
- **Environment:** Light, Barometer, Proximity, Temperature
- **Audio/Visual:** Microphone levels, Camera preview
- **Activity:** Step counter
- **Power:** Battery level + charging status

### Arduino Sensors (via USB)
- **HC-SR04** Ultrasonic distance sensor (2-400 cm)
- **ITR20001** IR line tracking (3 sensors)
- **MPU-6050** 6-axis IMU (accelerometer + gyroscope + temperature)
- **Battery monitor** (voltage sensing)

### Web Interface
Web UI accessible at `http://<phone-ip>:8080`
- **D-pad controls** with touch/click support
- **Keyboard controls** - WASD or arrow keys (auto-stop on release)
- **Speed slider** (50-255)
- **Live sensor data** (200ms refresh)
- **Connection status** indicator

## Quick Start

### Prerequisites
- **Hardware:**
  - Google Pixel 3 (Android 12)
  - ELEGOO Smart Robot Car V4.0 (Arduino Uno + ESP32-S3)
  - Powered USB-C hub
  - USB-A to USB-B cable (for Arduino connection)

- **Software:**
  - Android SDK (API 34)
  - JDK 17+
  - ADB (Android Debug Bridge)

### Build & Deploy

```bash
# Build the APK
./build.sh

# Install and launch on device
./deploy.sh

# Or do both in one command
./build.sh && ./deploy.sh
```

**Build time:** 2-7 seconds (incremental)  
**Deploy time:** ~3 seconds

### First Run
1. Connect Arduino Uno via USB hub to Pixel 3
2. Grant permissions: Camera, Microphone, Location, USB
3. Navigate to **Pixel Sensors** page to see phone sensors
4. Navigate to **Arduino Sensors** page to see robot sensors
5. Access web interface at `http://<phone-ip>:8080`

## Architecture

### Communication Flow
```
Web Browser → HTTP :8080 → USB Serial → Arduino → Motors
                  ↓                ↓
              Sensor API      Robot Sensors
```

## Project Structure

```
polly-android/
├── app/src/main/
│   ├── AndroidManifest.xml          # Permissions & config
│   ├── java/com/robotics/polly/
│   │   ├── MainActivity.kt           # App entry point
│   │   ├── PixelSensorsFragment.kt   # Phone sensors UI
│   │   ├── ArduinoSensorsFragment.kt # Robot sensors UI
│   │   ├── LidarFragment.kt          # LIDAR page (future)
│   │   ├── UsbSerialManager.kt       # USB communication + auto-reconnect
│   │   ├── PollyServer.kt            # HTTP/web server
│   │   ├── LogManager.kt             # Centralized logging
│   │   └── ViewPagerAdapter.kt       # Page navigation
│   └── res/
│       ├── layout/                   # Material Design card layouts
│       ├── menu/                     # Bottom navigation
│       └── values/                   # Colors, strings, themes
├── build.sh                          # Build script
├── deploy.sh                         # Deploy to device
└── README.md                         # This file
```

## API Endpoints

### HTTP Server (port 8080)

**Control:**
- `GET /control?action=forward&speed=128` - Move forward
- `GET /control?action=backward&speed=128` - Move backward  
- `GET /control?action=left&speed=128` - Turn left
- `GET /control?action=right&speed=128` - Turn right
- `GET /control?action=stop` - Stop motors

**Data:**
- `GET /sensors` - All Pixel 3 sensor data (JSON)
- `GET /` - Web interface (HTML)

**Response format:**
```json
{
  "status": "ok",
  "action": "forward",
  "speed": 128
}
```

## Arduino Protocol

JSON-based serial protocol at 115200 baud:

**Command format:**
```json
{"N": <command>, "D1": <data1>, "D2": <data2>}
```

**Common commands:**
- `{"N":2,"D1":128}` - Forward at speed 128
- `{"N":6}` - Stop
- `{"N":100}` - Get all sensors

**Response format:**
```json
{
  "ts": 12345,
  "execUs": 5648,
  "distance": 18,
  "ir": [666, 604, 809],
  "onLine": [1, 1, 1],
  "accel": [888, -192, 17936],
  "gyro": [-611, 122, -114],
  "temp": 26.9,
  "battery": 1.14,
  "mpuValid": 1
}
```

## Performance

- **Sensor refresh:** 200ms (5 Hz)
- **USB latency:** ~20ms per command
- **Camera:** 30 fps
- **Microphone:** 20 Hz sampling
- **Web refresh:** 200ms
- **Arduino execution:** 5.6ms per sensor sweep

## Dependencies

```gradle
// Core
implementation 'androidx.core:core-ktx:1.12.0'
implementation 'androidx.appcompat:appcompat:1.6.1'
implementation 'com.google.android.material:material:1.11.0'

// Camera
implementation 'androidx.camera:camera-core:1.3.1'
implementation 'androidx.camera:camera-camera2:1.3.1'
implementation 'androidx.camera:camera-lifecycle:1.3.1'
implementation 'androidx.camera:camera-view:1.3.1'

// USB Serial
implementation 'com.github.mik3y:usb-serial-for-android:3.7.3'

// HTTP Server
implementation 'org.nanohttpd:nanohttpd:2.3.1'

// Navigation
implementation 'androidx.viewpager2:viewpager2:1.0.0'
```

## Troubleshooting

### USB Not Connecting
- Check shield switch is in "UPLOAD" mode (not UART)
- Verify powered USB hub is connected
- Grant USB permission in Android settings
- Try unplugging/replugging USB cable

### Camera Not Working
- Grant Camera permission: Settings → Apps → Polly → Permissions
- Restart app with 🔄 button in toolbar

### Web Interface Shows Disconnected
- Check phone and computer are on same WiFi
- Find IP: `adb shell "ip addr show wlan0"`
- Verify port 8080 is accessible

### GPS Not Locking
- Move to window or outdoors
- GPS can take 2-5 minutes for first fix indoors
- Verify Location permission granted

## Future Plans

- [ ] **LIDAR Integration** - SLAMTEC A1 for 360° mapping
- [ ] **Autonomous Modes** - obstacle avoidance
- [ ] **3D Visualization** - Robot orientation with IMU data
- [ ] **Sensor Graphs** - Real-time charts for accelerometer/gyro
- [ ] **Camera Streaming** - MJPEG to web interface
- [ ] **Virtual Joystick** - Analog control instead of D-pad
- [ ] **Path Planning** - Navigate to waypoints
- [ ] **Dark Mode** - Theme toggle

## Credits

Built with ❤️ using:
- **Kotlin** - Modern Android development
- **CameraX** - Camera API
- **Material Design** - UI components
- **NanoHTTPD** - Lightweight HTTP server
- **usb-serial-for-android** - USB communication

## License

**License TBD.** This project integrates third-party SDKs (planned: FLIR One Gen 3, SLAMTEC LIDAR) whose licensing terms are being evaluated. An appropriate license will be added once SDK requirements are clear.

---

**Last Updated:** 2026-02-10  
**Version:** 1.0  
**Status:** ✅ Fully Operational
