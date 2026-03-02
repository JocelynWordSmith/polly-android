package com.robotics.polly

import android.os.SystemClock
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import org.json.JSONObject
import kotlin.math.abs

class GamepadController(
    private val arduinoBridge: ArduinoBridge,
    private val onStopAutonomous: () -> Unit
) {
    private var lastSendTime = 0L
    private var lastDistCm = 999
    private var wasStopped = true

    // Arduino data listener — register on ArduinoBridge.localListeners
    val arduinoListener: (String) -> Unit = { line ->
        try {
            val json = JSONObject(line)
            val dist = json.optInt("dist_f", -1)
            if (dist > 0) lastDistCm = dist
        } catch (_: Exception) {}
    }

    fun handleMotionEvent(event: MotionEvent): Boolean {
        if (event.source and InputDevice.SOURCE_JOYSTICK == 0) return false

        val axisY = -event.getAxisValue(MotionEvent.AXIS_Y) // forward = positive
        val axisX = event.getAxisValue(MotionEvent.AXIS_X)  // right = positive

        val y = if (abs(axisY) < DEADZONE) 0f else axisY
        val x = if (abs(axisX) < DEADZONE) 0f else axisX

        // No input — send one stop then idle
        if (y == 0f && x == 0f) {
            if (!wasStopped) {
                sendStop()
                wasStopped = true
            }
            return true
        }

        // Stick moved — stop any autonomous mode first
        onStopAutonomous()

        // Arcade to differential drive
        val left = ((y + x) * MAX_SPEED).toInt().coerceIn(-255, 255)
        val right = ((y - x) * MAX_SPEED).toInt().coerceIn(-255, 255)

        // Ultrasonic e-stop: block forward motion when obstacle near
        val netForward = (left + right) / 2
        if (lastDistCm in 1..OBSTACLE_NEAR_CM && netForward > 0) {
            sendStop()
            wasStopped = true
            return true
        }

        // Throttle to 10Hz
        val now = SystemClock.elapsedRealtime()
        if (now - lastSendTime < SEND_INTERVAL_MS) return true
        lastSendTime = now

        arduinoBridge.sendCommand("""{"N":7,"D1":$left,"D2":$right}""")
        wasStopped = false
        return true
    }

    fun handleKeyEvent(event: KeyEvent): Boolean {
        if (event.source and InputDevice.SOURCE_GAMEPAD == 0 &&
            event.source and InputDevice.SOURCE_JOYSTICK == 0) return false

        if (event.action == KeyEvent.ACTION_DOWN && event.keyCode == KeyEvent.KEYCODE_BUTTON_B) {
            onStopAutonomous()
            sendStop()
            wasStopped = true
            return true
        }
        return false
    }

    private fun sendStop() {
        arduinoBridge.sendCommand("""{"N":6}""")
    }

    companion object {
        private const val DEADZONE = 0.15f
        private const val MAX_SPEED = 120
        private const val SEND_INTERVAL_MS = 100L
        private const val OBSTACLE_NEAR_CM = 20
    }
}
