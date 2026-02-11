#!/bin/bash
set -e

APK="app/build/outputs/apk/debug/app-debug.apk"

if [ ! -f "$APK" ]; then
    echo "APK not found. Run ./build.sh first"
    exit 1
fi

echo "Installing Polly on device..."
adb install -r "$APK"

echo "Launching app..."
adb shell am start -n com.robotics.polly/.MainActivity

echo "Deployed successfully!"
