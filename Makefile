SHELL := /bin/bash

.PHONY: build deploy deploy-restart clean \
	robot-cmd robot-status robot-log robot-restart robot-app-restart \
	arena-snap arena-video pull-dataset

# === Build & Deploy ===
build:
	./gradlew assembleDebug

deploy:
	./gradlew installDebug

deploy-restart: deploy
	@adb shell am force-stop com.robotics.polly
	@sleep 1
	@adb shell am start -n com.robotics.polly/.MainActivity
	@echo "Deployed and restarted"

# === Robot Control (ADB) ===

# Send a command to the robot via ADB broadcast. Usage: make robot-cmd CMD=start_wander
robot-cmd:
	@test -n "$(CMD)" || (echo "Usage: make robot-cmd CMD=<cmd>"; \
		echo "  Commands: start_map stop_map start_wander stop_wander"; \
		echo "            start_explore stop_explore"; \
		echo "            start_recording stop_recording retry_arduino retry_flir stop get_status"; exit 1)
	@adb shell am broadcast -n com.robotics.polly/.RemoteCommandReceiver -a com.robotics.polly.REMOTE_CMD --es cmd "$(CMD)" 2>&1

# Shorthand for get_status
robot-status:
	@adb shell am broadcast -n com.robotics.polly/.RemoteCommandReceiver -a com.robotics.polly.REMOTE_CMD --es cmd get_status 2>&1

# Grab recent app logs (last 200 lines)
robot-log:
	@adb logcat -d -s "PollyApp" "BridgeService" "RemoteCmd" "GridMapper" "WanderCtrl" "FrontierCtrl" -t 200

# Force-stop and relaunch the app
robot-restart robot-app-restart:
	@adb shell am force-stop com.robotics.polly
	@sleep 1
	@adb shell am start -n com.robotics.polly/.MainActivity
	@echo "App restarted"

# === Arena (remote testing) ===
# Webcam device — override with ARENA_CAM=/dev/videoN
ARENA_CAM ?= /dev/video0
ARENA_DIR ?= local/arena

# Capture a single frame from the arena webcam
arena-snap:
	@mkdir -p $(ARENA_DIR)
	@ffmpeg -f v4l2 -i $(ARENA_CAM) -frames:v 1 -y $(ARENA_DIR)/snap.jpg 2>/dev/null
	@echo "$(ARENA_DIR)/snap.jpg"

# Record N seconds of video (default 10s). Usage: make arena-video SECS=10
SECS ?= 10
arena-video:
	@mkdir -p $(ARENA_DIR)
	ffmpeg -f v4l2 -i $(ARENA_CAM) -t $(SECS) -y $(ARENA_DIR)/clip.mp4 2>&1 | tail -1
	@echo "$(ARENA_DIR)/clip.mp4"

# === Data ===

# Pull the latest dataset from the phone
pull-dataset:
	@latest=$$(adb shell ls -td /sdcard/Android/data/com.robotics.polly/files/datasets/dataset_* 2>/dev/null | head -1); \
	if [ -z "$$latest" ]; then echo "No datasets found on device"; exit 1; fi; \
	mkdir -p local/datasets; \
	echo "Pulling $$latest"; \
	adb pull "$$latest" local/datasets/

# === Utility ===
clean:
	./gradlew clean
