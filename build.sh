#!/bin/bash
set -e

export JAVA_HOME=/usr/local/Cellar/openjdk@17/17.0.18/libexec/openjdk.jdk/Contents/Home

echo "Building Polly native Android app..."
$JAVA_HOME/bin/java -version

./gradlew assembleDebug

echo "Build complete: app/build/outputs/apk/debug/app-debug.apk"
