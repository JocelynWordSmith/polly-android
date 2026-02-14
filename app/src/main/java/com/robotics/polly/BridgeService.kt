package com.robotics.polly

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.wifi.WifiManager
import android.os.Binder
import android.os.IBinder
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.lifecycle.LifecycleOwner
import java.net.Inet4Address
import java.net.NetworkInterface

class BridgeService : Service() {

    private val binder = BridgeBinder()
    private var wsServer: PollyWebSocketServer? = null
    private var arduinoBridge: ArduinoBridge? = null
    private var lidarBridge: LidarBridge? = null
    private var flirBridge: FlirBridge? = null
    private var cameraBridge: CameraBridge? = null
    private var imuBridge: ImuBridge? = null
    private val wsPort = 8080
    private val handler = Handler(Looper.getMainLooper())

    inner class BridgeBinder : Binder() {
        fun getService(): BridgeService = this@BridgeService
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "BridgeService created")

        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification("Starting..."))

        startWebSocketServer()
        startBridges()
        startNotificationUpdater()
    }

    private fun startNotificationUpdater() {
        val updater = object : Runnable {
            override fun run() {
                val devices = mutableListOf<String>()
                if (arduinoBridge?.isConnected == true) devices.add("ARD")
                if (lidarBridge?.isConnected == true) devices.add("LDR")
                if (flirBridge?.isConnected == true) devices.add("FLR")
                if (cameraBridge?.isConnected == true) devices.add("CAM")
                if (imuBridge?.isConnected == true) devices.add("IMU")

                val ip = getLocalIpAddress()
                val clients = wsServer?.totalClientCount() ?: 0
                val devStr = if (devices.isNotEmpty()) devices.joinToString(" ") else "no devices"
                updateNotification("$ip:$wsPort | $devStr | ${clients}c")
                handler.postDelayed(this, 5000)
            }
        }
        handler.postDelayed(updater, 3000)
    }

    private fun startWebSocketServer() {
        try {
            wsServer = PollyWebSocketServer(wsPort)
            wsServer?.onControlMessage = { message ->
                handleControlMessage(message)
            }
            wsServer?.start()

            val ip = getLocalIpAddress()
            Log.d(TAG, "WebSocket server started on $ip:$wsPort")
            LogManager.success("Bridge server started on $ip:$wsPort")
            updateNotification("$ip:$wsPort")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start WebSocket server: ${e.message}", e)
            LogManager.error("Bridge server failed: ${e.message}")
        }
    }

    private fun startBridges() {
        val server = wsServer ?: return

        arduinoBridge = ArduinoBridge(this, server)
        arduinoBridge?.start()

        lidarBridge = LidarBridge(this, server)
        lidarBridge?.start()

        flirBridge = FlirBridge(this, server)
        flirBridge?.start()

        // Camera requires LifecycleOwner — started when activity binds via startCamera()
        cameraBridge = CameraBridge(this, server)

        imuBridge = ImuBridge(this, server)
        imuBridge?.start()
    }

    /**
     * Called by MainActivity after binding to start camera with lifecycle.
     */
    fun startCamera(lifecycleOwner: LifecycleOwner) {
        cameraBridge?.startWithLifecycle(lifecycleOwner)
    }

    private fun handleControlMessage(message: String) {
        try {
            val json = org.json.JSONObject(message)
            val target = json.optString("target", "")
            Log.d(TAG, "Control message for target: $target")
            when (target) {
                "arduino" -> arduinoBridge?.handleCommand(json)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Invalid control message: ${e.message}")
        }
    }

    fun getArduinoBridge(): ArduinoBridge? = arduinoBridge
    fun getLidarBridge(): LidarBridge? = lidarBridge
    fun getFlirBridge(): FlirBridge? = flirBridge
    fun getCameraBridge(): CameraBridge? = cameraBridge
    fun getImuBridge(): ImuBridge? = imuBridge
    fun getWebSocketServer(): PollyWebSocketServer? = wsServer

    fun getStatus(): Map<String, Any> {
        val server = wsServer
        return mapOf(
            "running" to (server != null),
            "port" to wsPort,
            "ip" to getLocalIpAddress(),
            "clients" to (server?.totalClientCount() ?: 0),
            "arduinoConnected" to (arduinoBridge?.isConnected ?: false),
            "lidarConnected" to (lidarBridge?.isConnected ?: false),
            "flirConnected" to (flirBridge?.isConnected ?: false),
            "cameraConnected" to (cameraBridge?.isConnected ?: false),
            "imuConnected" to (imuBridge?.isConnected ?: false),
            "endpoints" to mapOf(
                "arduino" to (server?.arduinoClients?.size ?: 0),
                "lidar" to (server?.lidarClients?.size ?: 0),
                "camera" to (server?.cameraClients?.size ?: 0),
                "flir" to (server?.flirClients?.size ?: 0),
                "imu" to (server?.imuClients?.size ?: 0),
                "control" to (server?.controlClients?.size ?: 0)
            )
        )
    }

    fun getLocalIpAddress(): String {
        try {
            val wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            @Suppress("DEPRECATION")
            val wifiInfo = wifiManager.connectionInfo
            @Suppress("DEPRECATION")
            val ip = wifiInfo.ipAddress
            if (ip != 0) {
                return "${ip and 0xff}.${ip shr 8 and 0xff}.${ip shr 16 and 0xff}.${ip shr 24 and 0xff}"
            }

            for (iface in NetworkInterface.getNetworkInterfaces()) {
                for (addr in iface.inetAddresses) {
                    if (!addr.isLoopbackAddress && addr is Inet4Address) {
                        return addr.hostAddress ?: "unknown"
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get IP: ${e.message}")
        }
        return "unknown"
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Polly Bridge",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Polly bridge service status"
        }
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(channel)
    }

    private fun buildNotification(statusText: String): Notification {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("Polly Bridge")
            .setContentText(statusText)
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }

    fun updateNotification(statusText: String) {
        val nm = getSystemService(NotificationManager::class.java)
        nm.notify(NOTIFICATION_ID, buildNotification(statusText))
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "BridgeService destroying")
        handler.removeCallbacksAndMessages(null)

        imuBridge?.stop()
        imuBridge = null
        cameraBridge?.stop()
        cameraBridge = null
        flirBridge?.stop()
        flirBridge = null
        lidarBridge?.stop()
        lidarBridge = null
        arduinoBridge?.stop()
        arduinoBridge = null

        try {
            wsServer?.stop()
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping WS server: ${e.message}")
        }
        wsServer = null

        LogManager.info("Bridge service stopped")
    }

    companion object {
        private const val TAG = "BridgeService"
        private const val CHANNEL_ID = "polly_bridge"
        private const val NOTIFICATION_ID = 1001
    }
}
