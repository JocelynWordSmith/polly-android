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
import android.app.Activity
import android.util.Log
import androidx.lifecycle.LifecycleOwner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import java.lang.ref.WeakReference
import java.net.Inet4Address
import java.net.NetworkInterface

class BridgeService : Service() {

    private val binder = BridgeBinder()
    private var wsServer: PollyWebSocketServer? = null
    private var arduinoBridge: ArduinoBridge? = null
    private var flirBridge: FlirBridge? = null
    private var cameraBridge: CameraBridge? = null
    private var imuBridge: ImuBridge? = null
    private val wsPort = 8080
    private var activityRef: WeakReference<Activity>? = null
    var mapMode = false
        private set
    private var gridMapper: GridMapper? = null
    private var mapArduinoListener: ((String) -> Unit)? = null
    private var wanderController: WanderController? = null
    private var frontierController: FrontierController? = null
    private var datasetRecorder: DatasetRecorder? = null
    private val wanderScope = CoroutineScope(Dispatchers.Default)
    private val handler = Handler(Looper.getMainLooper())

    inner class BridgeBinder : Binder() {
        fun getService(): BridgeService = this@BridgeService
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onCreate() {
        super.onCreate()
        instance = this
        Log.d(TAG, "BridgeService created")

        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification("Starting..."))

        startWebSocketServer()
        startBridges()
        startNotificationUpdater()
        startReconnectWatchdog()
    }

    private fun startNotificationUpdater() {
        val updater = object : Runnable {
            override fun run() {
                val devices = mutableListOf<String>()
                if (arduinoBridge?.isConnected == true) devices.add("ARD")
                if (flirBridge?.isConnected == true) devices.add("FLR")
                if (mapMode) devices.add("MAP")
                else if (cameraBridge?.isConnected == true) devices.add("CAM")
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

    // Per-bridge retry state
    private var arduinoRetryCount = 0
    private var flirRetryCount = 0
    var arduinoRetryExhausted = false
        private set
    var flirRetryExhausted = false
        private set

    private fun startReconnectWatchdog() {
        scheduleRetrySequence("arduino")
        // FLIR auto-retry disabled — hardware disconnected. Use retryBridge("flir") to trigger manually.
    }

    private fun scheduleRetrySequence(bridge: String) {
        val retryRunnable = object : Runnable {
            override fun run() {
                when (bridge) {
                    "arduino" -> {
                        if (arduinoBridge?.isConnected == true) {
                            arduinoRetryCount = 0
                            arduinoRetryExhausted = false
                            return
                        }
                        arduinoRetryCount++
                        if (arduinoRetryCount > MAX_RETRIES) {
                            arduinoRetryExhausted = true
                            Log.d(TAG, "Arduino retry exhausted after $MAX_RETRIES attempts")
                            LogManager.warn("Arduino: Retry exhausted")
                            return
                        }
                        Log.d(TAG, "Arduino retry $arduinoRetryCount/$MAX_RETRIES")
                        LogManager.info("Arduino: Retry $arduinoRetryCount/$MAX_RETRIES")
                        arduinoBridge?.reconnect()
                        handler.postDelayed(this, RETRY_DELAY_MS)
                    }
                    "flir" -> {
                        if (flirBridge?.isConnected == true) {
                            flirRetryCount = 0
                            flirRetryExhausted = false
                            return
                        }
                        flirRetryCount++
                        if (flirRetryCount > MAX_RETRIES) {
                            flirRetryExhausted = true
                            Log.d(TAG, "FLIR retry exhausted after $MAX_RETRIES attempts")
                            LogManager.warn("FLIR: Retry exhausted")
                            return
                        }
                        Log.d(TAG, "FLIR retry $flirRetryCount/$MAX_RETRIES")
                        LogManager.info("FLIR: Retry $flirRetryCount/$MAX_RETRIES")
                        flirBridge?.reconnect()
                        handler.postDelayed(this, RETRY_DELAY_MS)
                    }
                }
            }
        }
        handler.postDelayed(retryRunnable, RETRY_DELAY_MS)
    }

    /** Called from UI when user taps Reconnect button. */
    fun retryBridge(bridge: String) {
        when (bridge) {
            "arduino" -> {
                arduinoRetryCount = 0
                arduinoRetryExhausted = false
                scheduleRetrySequence("arduino")
            }
            "flir" -> {
                flirRetryCount = 0
                flirRetryExhausted = false
                scheduleRetrySequence("flir")
            }
        }
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

        flirBridge = FlirBridge(this, server)
        // FLIR auto-start disabled — hardware disconnected. Use retryBridge("flir") to start manually.
        Log.d(TAG, "FLIR auto-start disabled (hardware disconnected)")

        // Camera requires LifecycleOwner — started when activity binds via startCamera()
        cameraBridge = CameraBridge(this, server)

        imuBridge = ImuBridge(this, server)
        imuBridge?.start()
    }

    /**
     * Called by MainActivity after binding to start camera with lifecycle.
     */
    fun startCamera(lifecycleOwner: LifecycleOwner) {
        if (lifecycleOwner is Activity) {
            activityRef = WeakReference(lifecycleOwner)
        }
        cameraBridge?.startWithLifecycle(lifecycleOwner)
    }

    fun startMapMode() {
        if (mapMode) return
        val server = wsServer ?: return

        LogManager.info("Map: Entering map mode")
        cameraBridge?.stop()

        val mapper = GridMapper()
        gridMapper = mapper

        val listener: (String) -> Unit = { line -> mapper.onArduinoData(line) }
        mapArduinoListener = listener
        arduinoBridge?.localListeners?.add(listener)

        mapMode = true
        updateNotification("MAP | ${getLocalIpAddress()}:$wsPort")
    }

    fun stopMapMode() {
        if (!mapMode) return
        LogManager.info("Map: Exiting map mode")

        // Stop recording if active
        if (datasetRecorder?.isRecording == true) stopRecording()

        // Save grid data before cleanup
        gridMapper?.let { mapper ->
            try {
                val json = mapper.grid.toJson()
                json.put("updates", mapper.updateCount)
                json.put("rejected", mapper.rejectedCount)
                json.put("corrections", mapper.correctionCount)
                json.put("raw_log", mapper.rawLogToJson())
                val ts = java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.US)
                    .format(java.util.Date())
                val file = java.io.File(getExternalFilesDir(null), "map_$ts.json")
                file.writeText(json.toString())
                Log.i(TAG, "Map saved: ${file.absolutePath}")
                LogManager.success("Map saved: ${file.name}")
            } catch (e: Exception) {
                Log.e(TAG, "Map save failed: ${e.message}")
            }
        }

        mapArduinoListener?.let { arduinoBridge?.localListeners?.remove(it) }
        mapArduinoListener = null

        mapMode = false

        // Restart CameraX
        val server = wsServer ?: return
        cameraBridge = CameraBridge(this, server)
        val activity = activityRef?.get()
        if (activity is LifecycleOwner) {
            cameraBridge?.startWithLifecycle(activity)
        }
    }

    fun getGridMapper(): GridMapper? = gridMapper

    fun startWanderMode() {
        if (wanderController?.isRunning == true) return
        startMapMode()
        val arduino = arduinoBridge ?: return
        val mapper = gridMapper ?: return
        val wander = WanderController(arduino, mapper)
        wanderController = wander
        wander.start(wanderScope)
        updateNotification("WANDER | ${getLocalIpAddress()}:$wsPort")
    }

    fun stopWanderMode() {
        wanderController?.stop()
        wanderController = null
        stopMapMode()
    }

    fun isWandering(): Boolean = wanderController?.isRunning == true

    fun startExploreMode() {
        if (frontierController?.isRunning == true) return
        startMapMode()
        val arduino = arduinoBridge ?: return
        val mapper = gridMapper ?: return
        val explore = FrontierController(arduino, mapper)
        frontierController = explore
        explore.start(wanderScope)
        updateNotification("EXPLORE | ${getLocalIpAddress()}:$wsPort")
    }

    fun stopExploreMode() {
        frontierController?.stop()
        frontierController = null
        stopMapMode()
    }

    fun isExploring(): Boolean = frontierController?.isRunning == true

    fun isExplorationComplete(): Boolean = frontierController?.explorationComplete == true

    // ---- Dataset recording ----

    fun startRecording(): String? {
        if (!mapMode) return null
        if (datasetRecorder?.isRecording == true) return null

        val recorder = DatasetRecorder(getExternalFilesDir("datasets") ?: return null)
        val dir = recorder.start()
        datasetRecorder = recorder

        // IMU recording at full sensor rate
        imuBridge?.recordingListener = { ts, wx, wy, wz, ax, ay, az ->
            recorder.recordImu(ts, wx, wy, wz, ax, ay, az)
        }
        imuBridge?.restartWithRate(fastest = true)

        Log.i(TAG, "Dataset recording started: ${dir.absolutePath}")
        return dir.absolutePath
    }

    fun stopRecording() {
        val recorder = datasetRecorder ?: return

        // Clear IMU recording and restore normal rate
        imuBridge?.recordingListener = null
        imuBridge?.restartWithRate(fastest = false)

        recorder.stop()
        datasetRecorder = null
        Log.i(TAG, "Dataset recording stopped")
    }

    fun isRecording(): Boolean = datasetRecorder?.isRecording == true
    fun getRecordedFrameCount(): Int = datasetRecorder?.frameCount ?: 0

    private fun handleControlMessage(message: String) {
        try {
            val json = org.json.JSONObject(message)
            val target = json.optString("target", "")
            Log.d(TAG, "Control message for target: $target")
            when (target) {
                "arduino" -> {
                    if (arduinoBridge == null) {
                        LogManager.warn("Arduino bridge is null, command dropped")
                    }
                    arduinoBridge?.handleCommand(json)
                }
                "map" -> {
                    val cmd = json.optString("cmd", "")
                    handler.post {
                        when (cmd) {
                            "start" -> startMapMode()
                            "stop" -> stopMapMode()
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Invalid control message: ${e.message}")
            LogManager.error("Invalid control msg: ${e.message}")
        }
    }

    fun getArduinoBridge(): ArduinoBridge? = arduinoBridge
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
            "arduinoRetryExhausted" to arduinoRetryExhausted,
            "flirConnected" to (flirBridge?.isConnected ?: false),
            "flirRetryExhausted" to flirRetryExhausted,
            "cameraConnected" to (cameraBridge?.isConnected ?: false),
            "mapMode" to mapMode,
            "imuConnected" to (imuBridge?.isConnected ?: false),
            "endpoints" to mapOf(
                "arduino" to (server?.arduinoClients?.size ?: 0),
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
        instance = null
        Log.d(TAG, "BridgeService destroying")
        handler.removeCallbacksAndMessages(null)

        // Stop active modes
        frontierController?.stop()
        wanderController?.stop()

        mapArduinoListener?.let { arduinoBridge?.localListeners?.remove(it) }
        mapArduinoListener = null
        gridMapper = null

        imuBridge?.stop()
        imuBridge = null
        cameraBridge?.stop()
        cameraBridge = null
        flirBridge?.stop()
        flirBridge = null
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
        private const val RETRY_DELAY_MS = 3_000L
        private const val MAX_RETRIES = 3

        /** Singleton ref for RemoteCommandReceiver. Set in onCreate/onDestroy. */
        var instance: BridgeService? = null
            private set
    }
}
