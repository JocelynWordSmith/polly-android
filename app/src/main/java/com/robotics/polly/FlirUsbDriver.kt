package com.robotics.polly

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager

import android.hardware.usb.UsbRequest
import android.os.Build
import android.util.Log
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Pure Kotlin USB driver for FLIR One Gen 3 (standard, Lepton 2.5, 80x60).
 *
 * Replaces the deprecated flironesdk.aar with direct USB Host API communication,
 * eliminating the 32-bit native library dependency that conflicts with ARCore (arm64).
 *
 * Protocol from community reverse-engineering:
 *   - USB config 3, interfaces 0/1/2
 *   - EP 0x85 (IN): frame data (thermal + visual JPEG + status JSON)
 *   - EP 0x02 (OUT): control commands
 *   - EP 0x81 (IN): control responses
 *   - Frame: 28-byte header (magic 0xEFBE0000) + thermal + JPEG + status
 *   - Thermal: 80x60, stride 82, 16-bit LE, 32-byte offset within thermal section
 */
class FlirUsbDriver(private val context: Context) {

    /** Parsed thermal frame delivered to listeners. */
    data class ThermalFrame(
        val width: Int,          // 80
        val height: Int,         // 60
        val rawPixels: IntArray, // 80*60 uint16 values (14-bit radiometric counts)
        val minVal: Int,
        val maxVal: Int,
        val jpegVisual: ByteArray?, // embedded visual camera JPEG (may be empty)
        val statusJson: String?,    // embedded status JSON (FFC state, etc.)
    )

    interface FrameListener {
        fun onFrame(frame: ThermalFrame)
        fun onFfcEvent()
    }

    var frameListener: FrameListener? = null
    var isConnected = false
        private set

    private val usbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager
    private var connection: UsbDeviceConnection? = null
    private var device: UsbDevice? = null
    private var epIn: UsbEndpoint? = null     // EP 0x85 (frame data IN)
    private var epOut: UsbEndpoint? = null    // EP 0x02 (control OUT)
    private var epStatus: UsbEndpoint? = null // EP 0x81 (status/config IN)
    private var epFileIn: UsbEndpoint? = null // EP 0x83 (file I/O IN)
    private var epFileOut: UsbEndpoint? = null // EP 0x04 (file I/O OUT)

    @Volatile
    private var running = false
    private var readThread: Thread? = null
    private var drainThread: Thread? = null

    // Frame accumulation buffer (1 MB)
    private val frameBuf = ByteArray(FRAME_BUF_SIZE)
    private var frameBufPos = 0

    // FFC state tracking
    private var ffcActive = false
    private var dropNextFrame = false
    private var parsedFrameCount = 0

    private val usbReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            if (intent.action == ACTION_USB_PERMISSION) {
                val dev: UsbDevice? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
                }
                if (intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)) {
                    dev?.let { d -> Thread { openDevice(d) }.start() }
                } else {
                    Log.w(TAG, "USB permission denied for FLIR")
                    LogManager.warn("FLIR: USB permission denied")
                }
            }
        }
    }

    fun start() {
        val filter = IntentFilter(ACTION_USB_PERMISSION)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(usbReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            context.registerReceiver(usbReceiver, filter)
        }

        findDevice()
    }

    fun stop() {
        running = false
        readThread?.interrupt()
        readThread?.join(2000)
        readThread = null
        drainThread?.join(2000)
        drainThread = null

        connection?.close()
        connection = null
        device = null
        epIn = null
        epOut = null
        epStatus = null
        epFileIn = null
        epFileOut = null
        isConnected = false

        try {
            context.unregisterReceiver(usbReceiver)
        } catch (_: IllegalArgumentException) { }
    }

    fun reconnect() {
        if (isConnected) return
        Log.d(TAG, "Reconnect requested")
        findDevice()
    }

    // -- Device discovery --

    private fun findDevice() {
        val dev = usbManager.deviceList.values.firstOrNull { d ->
            d.vendorId == VENDOR_ID && d.productId == PRODUCT_ID
        }

        if (dev == null) {
            Log.d(TAG, "FLIR One not found in USB device list")
            LogManager.info("FLIR: Camera not found")
            return
        }

        Log.d(TAG, "Found FLIR One: ${dev.deviceName}")

        if (usbManager.hasPermission(dev)) {
            Thread { openDevice(dev) }.start()
        } else {
            val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                PendingIntent.FLAG_MUTABLE
            } else {
                0
            }
            val pi = PendingIntent.getBroadcast(context, 0, Intent(ACTION_USB_PERMISSION), flags)
            usbManager.requestPermission(dev, pi)
            LogManager.info("FLIR: Requesting USB permission...")
        }
    }

    // -- Device setup --

    private fun openDevice(dev: UsbDevice) {
        try {
            // Log device info for debugging
            Log.i(TAG, "Device: ${dev.deviceName} VID:0x${Integer.toHexString(dev.vendorId)} PID:0x${Integer.toHexString(dev.productId)}")
            Log.i(TAG, "  Configurations: ${dev.configurationCount}, Interfaces: ${dev.interfaceCount}")
            for (i in 0 until dev.interfaceCount) {
                val iface = dev.getInterface(i)
                val eps = (0 until iface.endpointCount).map { j ->
                    val ep = iface.getEndpoint(j)
                    "0x${Integer.toHexString(ep.address)}(${if (ep.direction == UsbConstants.USB_DIR_IN) "IN" else "OUT"})"
                }
                Log.i(TAG, "  Interface $i: id=${iface.id} alt=${iface.alternateSetting} class=${iface.interfaceClass} endpoints=$eps")
            }

            val conn = usbManager.openDevice(dev) ?: run {
                LogManager.error("FLIR: Failed to open USB device")
                return
            }

            // Only claim alt=0 interfaces (the ones with endpoints).
            // Claiming alt=1 doesn't help and setInterface is destructive on this device.
            for (i in 0 until dev.interfaceCount) {
                val iface = dev.getInterface(i)
                if (iface.alternateSetting != 0) {
                    Log.d(TAG, "Skip interface $i (id=${iface.id} alt=${iface.alternateSetting})")
                    continue
                }
                val claimed = conn.claimInterface(iface, true)
                Log.i(TAG, "Claim interface $i (id=${iface.id} alt=${iface.alternateSetting}): $claimed")
            }

            // Find endpoints
            findEndpoints(dev)

            if (epIn == null) {
                LogManager.error("FLIR: Could not find frame endpoint (EP 0x85)")
                conn.close()
                return
            }

            device = dev
            connection = conn

            // Run init sequence (currently no-op — see initCamera comment)
            initCamera(conn, dev)

            // Start reading frames
            isConnected = true
            LogManager.success("FLIR: Camera connected (USB)")
            startReadThread()

        } catch (e: Exception) {
            Log.e(TAG, "Failed to open FLIR device: ${e.message}", e)
            LogManager.error("FLIR: Open failed: ${e.message}")
        }
    }

    private fun findEndpoints(dev: UsbDevice) {
        for (i in 0 until dev.interfaceCount) {
            val iface = dev.getInterface(i)
            for (j in 0 until iface.endpointCount) {
                val ep = iface.getEndpoint(j)
                val typeStr = when (ep.type) {
                    UsbConstants.USB_ENDPOINT_XFER_CONTROL -> "CTRL"
                    UsbConstants.USB_ENDPOINT_XFER_ISOC -> "ISO"
                    UsbConstants.USB_ENDPOINT_XFER_BULK -> "BULK"
                    UsbConstants.USB_ENDPOINT_XFER_INT -> "INT"
                    else -> "?"
                }
                Log.i(TAG, "  EP 0x${Integer.toHexString(ep.address)} type=$typeStr maxPacket=${ep.maxPacketSize} iface=$i(id=${iface.id} alt=${iface.alternateSetting})")
                when (ep.address) {
                    0x85 -> epIn = ep      // frame data IN
                    0x81 -> epStatus = ep  // config/status IN
                    0x02 -> epOut = ep     // config/control OUT
                    0x83 -> epFileIn = ep  // file I/O IN
                    0x04 -> epFileOut = ep // file I/O OUT
                }
            }
        }
        Log.i(TAG, "Endpoints: frame=${epIn != null}, status=${epStatus != null}, control=${epOut != null}, fileIn=${epFileIn != null}, fileOut=${epFileOut != null}")
    }

    private fun initCamera(conn: UsbDeviceConnection, dev: UsbDevice) {
        // DO NOT use conn.setInterface() — it hangs 5s and corrupts device state.
        // DO NOT send GET_CONFIGURATION, GET_INTERFACE, or CLEAR_HALT.
        //
        // Raw controlTransfer init (community convention: wValue=0=stop, wValue=1=start).
        val r1 = conn.controlTransfer(0x01, 0x0B, 0, 2, null, 0, 100)
        Log.i(TAG, "Init stop frames (wVal=0, iface=2): $r1")

        val r2 = conn.controlTransfer(0x01, 0x0B, 0, 1, null, 0, 100)
        Log.i(TAG, "Init stop fileIO (wVal=0, iface=1): $r2")

        val r3 = conn.controlTransfer(0x01, 0x0B, 1, 1, null, 0, 100)
        Log.i(TAG, "Init start fileIO (wVal=1, iface=1): $r3")

        val trigger = byteArrayOf(0, 0)
        val r4 = conn.controlTransfer(0x01, 0x0B, 1, 2, trigger, 2, 200)
        Log.i(TAG, "Init start frames (wVal=1, iface=2, 2-byte): $r4")
    }

    // -- Frame reading --

    private fun startReadThread() {
        running = true
        frameBufPos = 0

        val conn = connection ?: return
        val epFrame = epIn ?: return
        val epConfig = epStatus
        val epFile = epFileIn

        // Drain thread: sync bulkTransfer on EP 0x81 + EP 0x83
        // The device stalls if these aren't polled alongside EP 0x85.
        drainThread = Thread({
            Log.i(TAG, "Drain thread started (EP 0x81/0x83)")
            val configBuf = ByteArray(BULK_TRANSFER_SIZE)
            val fileBuf = ByteArray(BULK_TRANSFER_SIZE)
            var configTotal = 0L
            var fileTotal = 0L
            try {
                while (running) {
                    if (epConfig != null) {
                        val n = conn.bulkTransfer(epConfig, configBuf, configBuf.size, 50)
                        if (n > 0) configTotal += n
                    }
                    if (epFile != null) {
                        val n = conn.bulkTransfer(epFile, fileBuf, fileBuf.size, 50)
                        if (n > 0) fileTotal += n
                    }
                }
            } catch (e: Exception) {
                if (running) Log.e(TAG, "Drain thread exception: ${e.message}", e)
            }
            Log.i(TAG, "Drain thread stopped (config=${configTotal}B, file=${fileTotal}B)")
        }, "FLIR-USB-Drain")
        drainThread?.isDaemon = true

        // Frame thread: reads from EP 0x85
        // Try sync bulkTransfer first (works if setInterface updated kernel table).
        // Fall back to async UsbRequest if sync returns -1.
        readThread = Thread({
            Log.i(TAG, "Frame thread started")

            // Start drain thread first — device may stall without EP 0x81/0x83 draining
            drainThread?.start()

            // Probe: try sync bulkTransfer on EP 0x85
            val probeBuf = ByteArray(BULK_TRANSFER_SIZE)
            val probeN = conn.bulkTransfer(epFrame, probeBuf, probeBuf.size, 2000)
            Log.i(TAG, "EP 0x85 sync probe: $probeN bytes")

            val useSyncMode = probeN >= 0
            if (useSyncMode) {
                Log.i(TAG, "Using SYNC mode for EP 0x85")
                if (probeN > 0) feedData(probeBuf, probeN)
                syncFrameLoop(conn, epFrame)
            } else {
                Log.i(TAG, "Sync failed ($probeN), using ASYNC mode for EP 0x85")
                asyncFrameLoop(conn, epFrame)
            }

            if (running) {
                running = false; isConnected = false
                LogManager.info("FLIR: Camera disconnected")
            }
        }, "FLIR-USB-Frame")
        readThread?.isDaemon = true
        readThread?.start()
    }

    private fun syncFrameLoop(conn: UsbDeviceConnection, ep: UsbEndpoint) {
        val buf = ByteArray(BULK_TRANSFER_SIZE)
        var totalBytes = 0L
        var totalReads = 0
        var consecutiveErrors = 0
        try {
            while (running) {
                val n = conn.bulkTransfer(ep, buf, buf.size, BULK_TIMEOUT_MS)
                if (n > 0) {
                    consecutiveErrors = 0
                    totalBytes += n
                    totalReads++
                    if (totalReads <= 20 || totalReads % 100 == 0) {
                        Log.i(TAG, "Frame #$totalReads: $n bytes, first4=0x${
                            (0 until minOf(4, n)).joinToString("") { "%02x".format(buf[it].toInt() and 0xFF) }
                        }, total=${totalBytes / 1024}KB")
                    }
                    feedData(buf, n)
                } else if (n < 0) {
                    consecutiveErrors++
                    if (consecutiveErrors >= MAX_CONSECUTIVE_ERRORS) {
                        Log.e(TAG, "Sync: $consecutiveErrors consecutive errors, stopping")
                        break
                    }
                }
            }
        } catch (e: Exception) {
            if (running) Log.e(TAG, "Sync frame loop exception: ${e.message}", e)
        }
        Log.i(TAG, "Sync frame loop done ($totalReads reads, ${totalBytes / 1024}KB)")
    }

    private fun asyncFrameLoop(conn: UsbDeviceConnection, ep: UsbEndpoint) {
        val req = UsbRequest()
        if (!req.initialize(conn, ep)) {
            Log.e(TAG, "UsbRequest.initialize failed for EP 0x${ep.address.toString(16)}")
            return
        }
        val buf = ByteBuffer.allocate(BULK_TRANSFER_SIZE)
        var totalBytes = 0L
        var totalReads = 0
        var consecutiveEmpty = 0
        try {
            while (running) {
                buf.clear()
                req.clientData = buf
                @Suppress("DEPRECATION")
                if (!req.queue(buf, BULK_TRANSFER_SIZE)) {
                    Log.e(TAG, "Async: queue() failed")
                    break
                }
                val done = try {
                    conn.requestWait(5000)
                } catch (e: java.util.concurrent.TimeoutException) {
                    consecutiveEmpty++
                    if (consecutiveEmpty <= 5 || consecutiveEmpty % 10 == 0) {
                        Log.d(TAG, "Async: requestWait timeout #$consecutiveEmpty")
                    }
                    continue
                }
                if (done == null || !running) break

                val arr = buf.array()
                var n = 0
                for (i in BULK_TRANSFER_SIZE - 1 downTo 0) {
                    if (arr[i] != 0.toByte()) { n = i + 1; break }
                }

                totalReads++
                if (n > 0) {
                    consecutiveEmpty = 0
                    totalBytes += n
                    if (totalReads <= 20 || totalReads % 100 == 0) {
                        Log.i(TAG, "Frame #$totalReads: $n bytes, first4=0x${
                            (0 until minOf(4, n)).joinToString("") { "%02x".format(arr[it].toInt() and 0xFF) }
                        }, total=${totalBytes / 1024}KB")
                    }
                    feedData(arr, n)
                } else {
                    consecutiveEmpty++
                    if (consecutiveEmpty <= 5 || consecutiveEmpty % 1000 == 0) {
                        Log.d(TAG, "Async: empty read #$consecutiveEmpty")
                    }
                }
            }
        } catch (e: Exception) {
            if (running) Log.e(TAG, "Async frame loop exception: ${e.message}", e)
        } finally {
            req.close()
        }
        Log.i(TAG, "Async frame loop done ($totalReads reads, ${totalBytes / 1024}KB)")
    }

    /**
     * Accumulate incoming USB data and extract complete frames.
     * Frame boundary: magic bytes EF BE 00 00.
     */
    private fun feedData(data: ByteArray, length: Int) {
        // Append to buffer
        val remaining = FRAME_BUF_SIZE - frameBufPos
        val toCopy = length.coerceAtMost(remaining)
        System.arraycopy(data, 0, frameBuf, frameBufPos, toCopy)
        frameBufPos += toCopy

        // Scan for complete frames
        while (frameBufPos >= FRAME_HEADER_SIZE) {
            // Find magic at current position
            val magicPos = findMagic(frameBuf, 0, frameBufPos)
            if (magicPos < 0) {
                // No magic found — discard buffer
                frameBufPos = 0
                return
            }

            if (magicPos > 0) {
                // Discard data before magic
                System.arraycopy(frameBuf, magicPos, frameBuf, 0, frameBufPos - magicPos)
                frameBufPos -= magicPos
            }

            if (frameBufPos < FRAME_HEADER_SIZE) return

            // Parse header to get total frame size
            val bb = ByteBuffer.wrap(frameBuf, 8, 16).order(ByteOrder.LITTLE_ENDIAN)
            val frameSize = bb.int
            val thermalSize = bb.int
            val jpgSize = bb.int
            val statusSize = bb.int

            val totalSize = FRAME_HEADER_SIZE + frameSize
            if (totalSize > FRAME_BUF_SIZE || totalSize < FRAME_HEADER_SIZE) {
                // Invalid frame size — skip this magic and look for next
                System.arraycopy(frameBuf, 4, frameBuf, 0, frameBufPos - 4)
                frameBufPos -= 4
                continue
            }

            if (frameBufPos < totalSize) {
                // Incomplete frame — wait for more data
                return
            }

            // Complete frame — parse it
            parseFrame(frameBuf, thermalSize, jpgSize, statusSize)

            // Remove this frame from buffer
            val remaining2 = frameBufPos - totalSize
            if (remaining2 > 0) {
                System.arraycopy(frameBuf, totalSize, frameBuf, 0, remaining2)
            }
            frameBufPos = remaining2
        }
    }

    private fun findMagic(buf: ByteArray, start: Int, end: Int): Int {
        for (i in start until end - 3) {
            if (buf[i] == MAGIC[0] && buf[i + 1] == MAGIC[1] &&
                buf[i + 2] == MAGIC[2] && buf[i + 3] == MAGIC[3]
            ) {
                return i
            }
        }
        return -1
    }

    private fun parseFrame(buf: ByteArray, thermalSize: Int, jpgSize: Int, statusSize: Int) {
        // Parse status JSON (FFC detection)
        var statusJson: String? = null
        if (statusSize > 0) {
            val statusOffset = FRAME_HEADER_SIZE + thermalSize + jpgSize
            try {
                statusJson = String(buf, statusOffset, statusSize, Charsets.UTF_8)
                checkFfcState(statusJson)
            } catch (_: Exception) { }
        }

        // Skip frames during FFC
        if (ffcActive || dropNextFrame) {
            if (dropNextFrame) dropNextFrame = false
            return
        }

        // Parse thermal data (Lepton 2, 80x60, stride 82)
        // Community reference: pixel positions are from FRAME start (offset 32 = header 28 + 4 padding)
        // with a 4-byte gap in the middle of each row between x=39 and x=40.
        if (thermalSize < THERMAL_PIXEL_OFFSET + OWIDTH * 2) return // too small

        val pixels = IntArray(OWIDTH * OHEIGHT)
        var minVal = 65535
        var maxVal = 0

        for (y in 0 until OHEIGHT) {
            for (x in 0 until OWIDTH) {
                // Community formula: buf[2*(y*82+x) + 32] for x<40, buf[2*(y*82+x) + 36] for x>=40
                val linearOffset = 2 * (y * STRIDE + x)
                val pos = if (x < OWIDTH / 2) {
                    FRAME_HEADER_SIZE + THERMAL_PIXEL_OFFSET + linearOffset
                } else {
                    FRAME_HEADER_SIZE + THERMAL_PIXEL_OFFSET + ROW_MID_GAP + linearOffset
                }
                if (pos + 1 >= buf.size) continue
                val lo = buf[pos].toInt() and 0xFF
                val hi = buf[pos + 1].toInt() and 0xFF
                val value = lo or (hi shl 8)
                pixels[y * OWIDTH + x] = value
                if (value < minVal) minVal = value
                if (value > maxVal) maxVal = value
            }
        }

        // Extract JPEG visual (if present)
        var jpeg: ByteArray? = null
        if (jpgSize > 0) {
            val jpgOffset = FRAME_HEADER_SIZE + thermalSize
            jpeg = buf.copyOfRange(jpgOffset, jpgOffset + jpgSize)
        }

        parsedFrameCount++
        if (parsedFrameCount <= 3 || parsedFrameCount % 50 == 0) {
            Log.i(TAG, "Parsed frame #$parsedFrameCount: thermal=${thermalSize}B jpg=${jpgSize}B min=$minVal max=$maxVal")
        }

        frameListener?.onFrame(
            ThermalFrame(
                width = OWIDTH,
                height = OHEIGHT,
                rawPixels = pixels,
                minVal = minVal,
                maxVal = maxVal,
                jpegVisual = jpeg,
                statusJson = statusJson,
            )
        )
    }

    private fun checkFfcState(statusJson: String) {
        val wasFfc = ffcActive
        ffcActive = statusJson.contains("FFC_PROGRESS") || statusJson.contains("\"FFC\"")

        if (wasFfc && !ffcActive) {
            // FFC just ended — drop the next frame (often noisy)
            dropNextFrame = true
            frameListener?.onFfcEvent()
            Log.d(TAG, "FFC completed")
        } else if (!wasFfc && ffcActive) {
            Log.d(TAG, "FFC started (dropping frames)")
        }
    }

    companion object {
        private const val TAG = "FlirUsbDriver"
        private const val ACTION_USB_PERMISSION = "com.robotics.polly.FLIR_USB_PERMISSION"

        const val VENDOR_ID = 0x09CB
        const val PRODUCT_ID = 0x1996
        private const val USB_CONFIGURATION = 3

        // Frame parsing
        private val MAGIC = byteArrayOf(0xEF.toByte(), 0xBE.toByte(), 0x00, 0x00)
        private const val FRAME_HEADER_SIZE = 28
        private const val THERMAL_PIXEL_OFFSET = 4  // padding before pixel data within thermal section
        private const val ROW_MID_GAP = 4            // 4-byte gap between x=39 and x=40 each row

        // Gen 3 standard (Lepton 2.5): 80x60, stride 82
        const val OWIDTH = 80
        const val OHEIGHT = 60
        private const val STRIDE = 82

        // USB transfer params
        private const val FRAME_BUF_SIZE = 1_048_576 // 1 MB
        private const val BULK_TRANSFER_SIZE = 16384
        private const val BULK_TIMEOUT_MS = 100
        private const val MAX_CONSECUTIVE_ERRORS = 50
    }
}
