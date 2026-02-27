package com.robotics.polly

import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.YuvImage
import android.util.Log
import java.io.BufferedWriter
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Records camera frames, IMU, and poses to phone storage in EuRoC-compatible format.
 *
 * Storage layout:
 *   dataset_YYYYMMDD_HHmmss/
 *     cam0/{timestamp_ns}.jpg
 *     imu0.csv          (timestamp_ns,wx,wy,wz,ax,ay,az)
 *     poses.csv          (timestamp_ns,tx,ty,tz,qx,qy,qz,qw)
 *     metadata.json
 */
class DatasetRecorder(private val baseDir: File) {

    private var sessionDir: File? = null
    private var framesDir: File? = null
    private var imuWriter: BufferedWriter? = null
    private var poseWriter: BufferedWriter? = null
    private var ioExecutor: ExecutorService? = null

    @Volatile var isRecording = false
        private set
    @Volatile var frameCount = 0
        private set
    private var imuCount = 0
    private var poseCount = 0
    private var startTimeNs = 0L
    private var lastTimeNs = 0L
    private var imageWidth = 0
    private var imageHeight = 0

    fun start(): File {
        val ts = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val dir = File(baseDir, "dataset_$ts")
        dir.mkdirs()

        val frames = File(dir, "cam0")
        frames.mkdirs()

        sessionDir = dir
        framesDir = frames
        imuWriter = BufferedWriter(FileWriter(File(dir, "imu0.csv")))
        imuWriter?.write("#timestamp_ns,wx,wy,wz,ax,ay,az\n")
        poseWriter = BufferedWriter(FileWriter(File(dir, "poses.csv")))
        poseWriter?.write("#timestamp_ns,tx,ty,tz,qx,qy,qz,qw\n")

        frameCount = 0
        imuCount = 0
        poseCount = 0
        startTimeNs = 0L
        ioExecutor = Executors.newSingleThreadExecutor()
        isRecording = true

        Log.i(TAG, "Recording started: ${dir.absolutePath}")
        return dir
    }

    fun stop() {
        isRecording = false

        // Drain pending frame writes
        ioExecutor?.shutdown()
        ioExecutor?.awaitTermination(5, TimeUnit.SECONDS)
        ioExecutor = null

        imuWriter?.flush()
        imuWriter?.close()
        imuWriter = null
        poseWriter?.flush()
        poseWriter?.close()
        poseWriter = null

        writeMetadata()

        Log.i(TAG, "Recording stopped: $frameCount frames, $imuCount IMU, $poseCount poses")
        sessionDir = null
        framesDir = null
    }

    /**
     * Record a camera frame. NV21 bytes are compressed to JPEG on the IO thread.
     * Call from frame capture loop — returns immediately.
     */
    fun recordFrame(nv21: ByteArray, width: Int, height: Int, timestampNs: Long) {
        if (!isRecording) return
        if (startTimeNs == 0L) startTimeNs = timestampNs
        lastTimeNs = timestampNs
        if (imageWidth == 0) { imageWidth = width; imageHeight = height }
        frameCount++
        val dir = framesDir ?: return
        ioExecutor?.execute {
            try {
                val yuvImage = YuvImage(nv21, ImageFormat.NV21, width, height, null)
                val out = ByteArrayOutputStream()
                yuvImage.compressToJpeg(Rect(0, 0, width, height), 85, out)
                File(dir, "$timestampNs.jpg").writeBytes(out.toByteArray())
            } catch (e: Exception) {
                Log.w(TAG, "Failed to write frame: ${e.message}")
            }
        }
    }

    /** Record an IMU sample. Called at full sensor rate (~200-400Hz). */
    fun recordImu(timestampNs: Long, wx: Float, wy: Float, wz: Float, ax: Float, ay: Float, az: Float) {
        if (!isRecording) return
        imuCount++
        try {
            imuWriter?.write("$timestampNs,$wx,$wy,$wz,$ax,$ay,$az\n")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to write IMU: ${e.message}")
        }
    }

    /** Record a 6DoF pose. Called at ~30Hz from pose source. */
    fun recordPose(timestampNs: Long, tx: Float, ty: Float, tz: Float, qx: Float, qy: Float, qz: Float, qw: Float) {
        if (!isRecording) return
        poseCount++
        try {
            poseWriter?.write("$timestampNs,$tx,$ty,$tz,$qx,$qy,$qz,$qw\n")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to write pose: ${e.message}")
        }
    }

    private fun writeMetadata() {
        val dir = sessionDir ?: return
        val durationS = if (startTimeNs > 0 && lastTimeNs > startTimeNs) (lastTimeNs - startTimeNs) / 1_000_000_000.0 else 0.0

        val json = """
{
  "device": "Pixel 3",
  "camera": "pixel3_moment14mm",
  "image_size": [$imageWidth, $imageHeight],
  "frame_count": $frameCount,
  "imu_samples": $imuCount,
  "pose_samples": $poseCount,
  "duration_s": ${"%.1f".format(durationS)}
}
""".trimIndent()

        File(dir, "metadata.json").writeText(json)
    }

    companion object {
        private const val TAG = "DatasetRecorder"
    }
}
