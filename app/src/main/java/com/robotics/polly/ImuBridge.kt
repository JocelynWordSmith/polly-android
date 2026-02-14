package com.robotics.polly

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.util.Log

class ImuBridge(
    private val context: Context,
    private val wsServer: PollyWebSocketServer
) : SensorEventListener {

    private var sensorManager: SensorManager? = null
    private var lastSendMs = 0L

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
        sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager

        val accel = sensorManager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        val gyro = sensorManager?.getDefaultSensor(Sensor.TYPE_GYROSCOPE)

        if (accel != null) {
            sensorManager?.registerListener(this, accel, SensorManager.SENSOR_DELAY_GAME)
            isConnected = true
            Log.d(TAG, "Accelerometer registered")
        }
        if (gyro != null) {
            sensorManager?.registerListener(this, gyro, SensorManager.SENSOR_DELAY_GAME)
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

        // Throttle to 50Hz
        val now = System.currentTimeMillis()
        if (now - lastSendMs >= 20 && wsServer.imuClients.isNotEmpty()) {
            lastSendMs = now
            val json = """{"ts":$now,"ax":$ax,"ay":$ay,"az":$az,"gx":$gx,"gy":$gy,"gz":$gz}"""
            wsServer.broadcastText(wsServer.imuClients, json)
        }
    }

    override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) {}

    fun stop() {
        Log.d(TAG, "Stopping ImuBridge")
        sensorManager?.unregisterListener(this)
        sensorManager = null
        isConnected = false
    }

    companion object {
        private const val TAG = "ImuBridge"
    }
}
