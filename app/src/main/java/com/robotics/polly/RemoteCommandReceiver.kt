package com.robotics.polly

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.util.Log
import org.json.JSONObject

/**
 * Receives ADB broadcast intents for remote control (no scrcpy needed).
 *
 * Usage:
 *   adb shell am broadcast -a com.robotics.polly.REMOTE_CMD --es cmd start_wander
 *   adb shell am broadcast -a com.robotics.polly.REMOTE_CMD --es cmd get_status
 */
class RemoteCommandReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val cmd = intent.getStringExtra("cmd") ?: run {
            Log.w(TAG, "REMOTE_CMD missing 'cmd' extra")
            resultCode = Activity.RESULT_CANCELED
            resultData = """{"error":"missing cmd extra"}"""
            return
        }

        val service = BridgeService.instance
        if (service == null) {
            Log.w(TAG, "REMOTE_CMD '$cmd' — BridgeService not running")
            resultCode = Activity.RESULT_CANCELED
            resultData = """{"error":"service not running"}"""
            return
        }

        Log.i(TAG, "REMOTE_CMD: $cmd")

        // Commands that touch UI must run on main thread
        val handler = Handler(Looper.getMainLooper())
        val pending = goAsync()

        handler.post {
            try {
                val result = dispatch(service, cmd)
                pending.resultCode = Activity.RESULT_OK
                pending.resultData = result.toString()
                Log.i(TAG, "POLLY_REMOTE: $cmd -> $result")
            } catch (e: Exception) {
                pending.resultCode = Activity.RESULT_CANCELED
                pending.resultData = """{"error":"${e.message}"}"""
                Log.e(TAG, "REMOTE_CMD '$cmd' failed: ${e.message}")
            } finally {
                pending.finish()
            }
        }
    }

    private fun dispatch(service: BridgeService, cmd: String): JSONObject {
        val result = JSONObject()
        result.put("cmd", cmd)

        when (cmd) {
            "start_map" -> {
                service.startMapMode()
                result.put("ok", true)
            }
            "stop_map" -> {
                service.stopMapMode()
                result.put("ok", true)
            }
            "start_wander" -> {
                service.startWanderMode()
                result.put("ok", true)
            }
            "stop_wander" -> {
                service.stopWanderMode()
                result.put("ok", true)
            }
            "start_explore" -> {
                service.startExploreMode()
                result.put("ok", true)
            }
            "stop_explore" -> {
                service.stopExploreMode()
                result.put("ok", true)
            }
            "start_recording" -> {
                val dir = service.startRecording()
                result.put("ok", dir != null)
                if (dir != null) result.put("dir", dir)
            }
            "stop_recording" -> {
                service.stopRecording()
                result.put("ok", true)
            }
            "retry_arduino" -> {
                service.retryBridge("arduino")
                result.put("ok", true)
            }
            "retry_flir" -> {
                service.retryBridge("flir")
                result.put("ok", true)
            }
            "stop" -> {
                // Universal stop — stop whatever is running
                when {
                    service.isExploring() -> service.stopExploreMode()
                    service.isWandering() -> service.stopWanderMode()
                    service.mapMode -> service.stopMapMode()
                }
                result.put("ok", true)
            }
            "get_status" -> {
                val status = service.getStatus()
                for ((k, v) in status) result.put(k, v)
                result.put("wandering", service.isWandering())
                result.put("exploring", service.isExploring())
                result.put("explorationComplete", service.isExplorationComplete())
                result.put("recording", service.isRecording())
                if (service.isRecording()) {
                    result.put("recordedFrames", service.getRecordedFrameCount())
                }
            }
            else -> {
                result.put("error", "unknown cmd: $cmd")
            }
        }
        return result
    }

    companion object {
        private const val TAG = "RemoteCmd"
    }
}
