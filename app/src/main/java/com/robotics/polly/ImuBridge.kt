package com.robotics.polly

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import android.os.HandlerThread
import android.util.Log

class ImuBridge(
    private val context: Context,
    private val wsServer: PollyWebSocketServer
) : SensorEventListener {

    private var sensorManager: SensorManager? = null
    private var handlerThread: HandlerThread? = null
    private var handler: Handler? = null
    private var lastSendNs = 0L

    /** High-rate recording callback. Invoked at full sensor rate (no throttle). */
    var recordingListener: ((Long, Float, Float, Float, Float, Float, Float) -> Unit)? = null

    private var ax = 0f
    private var ay = 0f
    private var az = 0f
    private var gx = 0f
    private var gy = 0f
    private var gz = 0f

    var isConnected = false
        private set

    fun start() {
        Log.d(TAG, "Starting ImuBridge")

        // Run sensor callbacks on a background thread so broadcastText
        // (which does network I/O) doesn't hit NetworkOnMainThreadException
        handlerThread = HandlerThread("ImuBridge").also { it.start() }
        handler = Handler(handlerThread!!.looper)

        sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager

        val accel = sensorManager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        val gyro = sensorManager?.getDefaultSensor(Sensor.TYPE_GYROSCOPE)

        if (accel != null) {
            sensorManager?.registerListener(this, accel, SensorManager.SENSOR_DELAY_GAME, handler)
            isConnected = true
            Log.d(TAG, "Accelerometer registered")
        }
        if (gyro != null) {
            sensorManager?.registerListener(this, gyro, SensorManager.SENSOR_DELAY_GAME, handler)
            Log.d(TAG, "Gyroscope registered")
        }

        LogManager.info("IMU: Sensors registered")
    }

    override fun onSensorChanged(event: SensorEvent) {
        when (event.sensor.type) {
            Sensor.TYPE_ACCELEROMETER -> {
                ax = event.values[0]
                ay = event.values[1]
                az = event.values[2]
            }
            Sensor.TYPE_GYROSCOPE -> {
                gx = event.values[0]
                gy = event.values[1]
                gz = event.values[2]
            }
        }

        val ts = event.timestamp

        // Only emit on gyro events (gyro is the master clock).
        // Accel values are the latest available — avoids sending stale gyro with accel timestamps.
        if (event.sensor.type == Sensor.TYPE_GYROSCOPE) {
            recordingListener?.invoke(ts, gx, gy, gz, ax, ay, az)

            // WebSocket: throttle to 200Hz (OKVIS2 needs high-rate IMU)
            if (ts - lastSendNs >= 5_000_000 && wsServer.imuClients.isNotEmpty()) {
                lastSendNs = ts
                val json = """{"ts":$ts,"ax":$ax,"ay":$ay,"az":$az,"gx":$gx,"gy":$gy,"gz":$gz}"""
                wsServer.broadcastText(wsServer.imuClients, json)
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) {}

    /**
     * Re-register sensors at a different rate.
     * Use fastest=true for dataset recording (~200-400Hz), false for normal operation (~62Hz).
     */
    fun restartWithRate(fastest: Boolean) {
        val sm = sensorManager ?: return
        val h = handler ?: return
        sm.unregisterListener(this)
        val delay = if (fastest) SensorManager.SENSOR_DELAY_FASTEST else SensorManager.SENSOR_DELAY_GAME
        val accel = sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        val gyro = sm.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
        if (accel != null) sm.registerListener(this, accel, delay, h)
        if (gyro != null) sm.registerListener(this, gyro, delay, h)
        Log.i(TAG, "IMU rate changed to ${if (fastest) "FASTEST" else "GAME"}")
    }

    fun stop() {
        Log.d(TAG, "Stopping ImuBridge")
        sensorManager?.unregisterListener(this)
        handlerThread?.quitSafely()
        handlerThread = null
        handler = null
        sensorManager = null
        isConnected = false
    }

    companion object {
        private const val TAG = "ImuBridge"
    }
}
