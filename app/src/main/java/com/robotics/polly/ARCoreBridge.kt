package com.robotics.polly

import android.app.Activity
import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.YuvImage
import android.media.Image
import android.opengl.EGL14
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.opengl.GLES20
import android.util.Log
import com.google.ar.core.ArCoreApk
import com.google.ar.core.CameraConfig
import com.google.ar.core.CameraConfigFilter
import com.google.ar.core.Config
import com.google.ar.core.Pose
import com.google.ar.core.Session
import com.google.ar.core.TrackingState
import com.google.ar.core.exceptions.CameraNotAvailableException
import com.google.ar.core.exceptions.UnavailableException
import java.io.ByteArrayOutputStream

class ARCoreBridge(
    private val wsServer: PollyWebSocketServer
) {
    private var session: Session? = null
    private var frameThread: Thread? = null
    @Volatile private var running = false
    private var cameraTextureId = 0
    private var mapMode = false

    /** Pose callback for map mode (lightweight, no image acquisition). */
    var poseListener: ((Pose, Long) -> Unit)? = null

    /** Frame data callback for dataset recording. Called at ~30fps with raw NV21 + timestamp. */
    var frameListener: ((ByteArray, Int, Int, Long) -> Unit)? = null

    /** Latest camera preview JPEG from map mode (~5fps). */
    @Volatile var latestPreviewJpeg: ByteArray? = null
        private set

    private var eglDisplay: EGLDisplay = EGL14.EGL_NO_DISPLAY
    private var eglContext: EGLContext = EGL14.EGL_NO_CONTEXT
    private var eglSurface: EGLSurface = EGL14.EGL_NO_SURFACE

    var isConnected = false
        private set

    /**
     * Start ARCore in lightweight map mode — pose tracking only.
     * No camera image acquisition, no point cloud accumulation.
     */
    fun startMapMode(activity: Activity) {
        if (session != null) return

        val availability = ArCoreApk.getInstance().checkAvailability(activity)
        if (!availability.isSupported) {
            Log.e(TAG, "ARCore not supported")
            LogManager.error("ARCore: Not supported on this device")
            return
        }

        try {
            val installStatus = ArCoreApk.getInstance().requestInstall(activity, true)
            if (installStatus == ArCoreApk.InstallStatus.INSTALL_REQUESTED) {
                LogManager.info("ARCore: Install requested")
                return
            }
        } catch (e: UnavailableException) {
            Log.e(TAG, "ARCore install check failed: ${e.message}")
            LogManager.error("ARCore: ${e.message}")
            return
        }

        try {
            val sess = Session(activity)
            selectCameraConfig(sess)
            val config = Config(sess)
            config.updateMode = Config.UpdateMode.BLOCKING
            config.focusMode = Config.FocusMode.FIXED
            sess.configure(config)
            session = sess

            mapMode = true
            running = true
            isConnected = true

            frameThread = Thread({ mapFrameLoop(sess) }, "ARCoreMapFrame")
            frameThread?.start()
        } catch (e: UnavailableException) {
            Log.e(TAG, "ARCore map mode failed: ${e.message}")
            LogManager.error("ARCore: ${e.message}")
            session = null
            isConnected = false
        }
    }

    private fun mapFrameLoop(sess: Session) {
        try {
            setupEgl()
            sess.setCameraTextureName(cameraTextureId)
            sess.resume()
            Log.d(TAG, "ARCore map mode started")
            LogManager.success("ARCore: Map mode tracking active")

            var frameCount = 0

            while (running) {
                try {
                    val frame = sess.update()
                    val camera = frame.camera
                    if (camera.trackingState != TrackingState.TRACKING) continue

                    poseListener?.invoke(camera.pose, frame.timestamp)

                    frameCount++
                    val fl = frameListener
                    if (fl != null) {
                        // Dataset recording: capture every frame at full rate
                        try {
                            val image = frame.acquireCameraImage()
                            try {
                                val nv21 = imageToNv21(image)
                                fl.invoke(nv21, image.width, image.height, frame.timestamp)
                                // Also update preview for UI (every 6th frame)
                                if (frameCount % 6 == 0) {
                                    latestPreviewJpeg = nv21ToJpeg(nv21, image.width, image.height)
                                }
                            } finally {
                                image.close()
                            }
                        } catch (_: Exception) {
                        }
                    } else if (frameCount % 6 == 0) {
                        // No recording: capture preview at ~5fps
                        try {
                            val image = frame.acquireCameraImage()
                            try {
                                latestPreviewJpeg = imageToJpeg(image)
                            } finally {
                                image.close()
                            }
                        } catch (_: Exception) {
                        }
                    }
                    if (frameCount % 300 == 0) {
                        val p = camera.pose
                        Log.i(TAG, "Map frame #$frameCount pos=(%.2f, %.2f, %.2f)".format(
                            p.tx(), p.ty(), p.tz()
                        ))
                    }
                } catch (e: Exception) {
                    if (running) Log.w(TAG, "Map frame error: ${e.message}")
                }
            }
        } catch (e: CameraNotAvailableException) {
            Log.e(TAG, "Camera not available: ${e.message}")
            LogManager.error("ARCore: Camera not available")
        } catch (e: Exception) {
            Log.e(TAG, "Map frame loop error: ${e.message}")
            LogManager.error("ARCore: ${e.message}")
        } finally {
            try { sess.pause() } catch (_: Exception) {}
            cleanupEgl()
            Log.d(TAG, "Map frame loop exited")
        }
    }

    private fun selectCameraConfig(sess: Session) {
        val filter = CameraConfigFilter(sess)
        val configs = sess.getSupportedCameraConfigs(filter)
        if (configs.isEmpty()) return

        // Sort by CPU image width descending, pick the first one that's <= 1280 wide
        // (720p is a good balance of quality vs thermal/bandwidth)
        val sorted = configs.sortedByDescending { it.imageSize.width }
        val target = sorted.firstOrNull { it.imageSize.width <= 1280 } ?: sorted.last()
        sess.cameraConfig = target
        Log.i(TAG, "Camera config: ${target.imageSize.width}x${target.imageSize.height} CPU image")
        LogManager.info("ARCore: ${target.imageSize.width}x${target.imageSize.height}")
    }

    /** Extract NV21 byte array from a camera Image. Fast (~2ms), no compression. */
    private fun imageToNv21(image: Image): ByteArray {
        val width = image.width
        val height = image.height
        val yPlane = image.planes[0]
        val uPlane = image.planes[1]
        val vPlane = image.planes[2]
        val yRowStride = yPlane.rowStride
        val uvRowStride = uPlane.rowStride
        val uvPixelStride = uPlane.pixelStride

        val nv21 = ByteArray(width * height * 3 / 2)

        // Y plane
        val yBuf = yPlane.buffer
        if (yRowStride == width) {
            yBuf.get(nv21, 0, width * height)
        } else {
            for (row in 0 until height) {
                yBuf.position(row * yRowStride)
                yBuf.get(nv21, row * width, width)
            }
        }

        // UV → NV21 (VU interleaved)
        var offset = width * height
        if (uvPixelStride == 2) {
            // Semi-planar: V buffer is already VUVU interleaved — bulk copy per row
            val vBuf = vPlane.buffer
            for (row in 0 until height / 2) {
                vBuf.position(row * uvRowStride)
                val bytesToRead = minOf(width, vBuf.remaining())
                vBuf.get(nv21, offset, bytesToRead)
                offset += width
            }
        } else {
            // Planar: per-pixel interleaving (rare)
            val uBuf = uPlane.buffer
            val vBuf = vPlane.buffer
            for (row in 0 until height / 2) {
                for (col in 0 until width / 2) {
                    val uvIdx = row * uvRowStride + col * uvPixelStride
                    nv21[offset++] = vBuf.get(uvIdx)
                    nv21[offset++] = uBuf.get(uvIdx)
                }
            }
        }

        return nv21
    }

    /** Compress NV21 bytes to JPEG. */
    private fun nv21ToJpeg(nv21: ByteArray, width: Int, height: Int): ByteArray {
        val yuvImage = YuvImage(nv21, ImageFormat.NV21, width, height, null)
        val out = ByteArrayOutputStream()
        yuvImage.compressToJpeg(Rect(0, 0, width, height), 75, out)
        return out.toByteArray()
    }

    /** Convert camera Image directly to JPEG. */
    private fun imageToJpeg(image: Image): ByteArray {
        return nv21ToJpeg(imageToNv21(image), image.width, image.height)
    }

    private fun setupEgl() {
        eglDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        EGL14.eglInitialize(eglDisplay, null, 0, null, 0)

        val configAttribs = intArrayOf(
            EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
            EGL14.EGL_SURFACE_TYPE, EGL14.EGL_PBUFFER_BIT,
            EGL14.EGL_NONE
        )
        val configs = arrayOfNulls<android.opengl.EGLConfig>(1)
        val numConfigs = intArrayOf(0)
        EGL14.eglChooseConfig(eglDisplay, configAttribs, 0, configs, 0, 1, numConfigs, 0)

        val contextAttribs = intArrayOf(
            EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE
        )
        eglContext = EGL14.eglCreateContext(
            eglDisplay, configs[0]!!, EGL14.EGL_NO_CONTEXT, contextAttribs, 0
        )

        val surfaceAttribs = intArrayOf(
            EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE
        )
        eglSurface = EGL14.eglCreatePbufferSurface(eglDisplay, configs[0]!!, surfaceAttribs, 0)

        EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)

        val textures = intArrayOf(0)
        GLES20.glGenTextures(1, textures, 0)
        cameraTextureId = textures[0]
    }

    private fun cleanupEgl() {
        if (eglDisplay != EGL14.EGL_NO_DISPLAY) {
            EGL14.eglMakeCurrent(
                eglDisplay, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT
            )
            if (eglSurface != EGL14.EGL_NO_SURFACE) {
                EGL14.eglDestroySurface(eglDisplay, eglSurface)
            }
            if (eglContext != EGL14.EGL_NO_CONTEXT) {
                EGL14.eglDestroyContext(eglDisplay, eglContext)
            }
            EGL14.eglTerminate(eglDisplay)
        }
        eglDisplay = EGL14.EGL_NO_DISPLAY
        eglContext = EGL14.EGL_NO_CONTEXT
        eglSurface = EGL14.EGL_NO_SURFACE
    }

    fun stop() {
        Log.d(TAG, "Stopping ARCoreBridge")
        running = false
        frameThread?.join(2000)
        frameThread = null
        session?.close()
        session = null
        isConnected = false
        mapMode = false
        poseListener = null
        frameListener = null
        latestPreviewJpeg = null
        LogManager.info("ARCore: Stopped")
    }

    companion object {
        private const val TAG = "ARCoreBridge"
    }
}
