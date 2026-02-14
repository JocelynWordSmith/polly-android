#!/bin/bash
# Fast deploy and screenshot for verification

set -e

cd "$(dirname "$0")"

# Use Android Studio's JDK
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"

echo "🔨 Building..."
./gradlew installDebug --quiet

echo "🚀 Launching app..."
adb shell am start -n com.robotics.polly/.MainActivity

echo "⏳ Waiting for app to load..."
sleep 3

echo "📸 Taking screenshot..."
adb exec-out screencap -p > /tmp/polly-verify.png

echo "✅ Screenshot saved to /tmp/polly-verify.png"
echo ""
echo "App deployed and running on device!"
