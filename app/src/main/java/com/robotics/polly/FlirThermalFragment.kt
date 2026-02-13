package com.robotics.polly

import android.graphics.Bitmap
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.fragment.app.Fragment
import com.flir.flironesdk.*
import java.nio.ByteBuffer
import java.util.EnumSet

class FlirThermalFragment : Fragment(), Device.Delegate, FrameProcessor.Delegate {
    
    companion object {
        private const val TAG = "FLIR"
    }
    
    private var flirDevice: Device? = null
    private var frameProcessor: FrameProcessor? = null
    private var thermalImageView: ImageView? = null
    private var statusText: TextView? = null
    private var sdkInitialized = false
    
    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        Log.d(TAG, "onCreateView called")
        LogManager.info("FLIR: Creating thermal camera view")
        return try {
            inflater.inflate(R.layout.fragment_flir_thermal, container, false)
        } catch (e: Exception) {
            Log.e(TAG, "onCreateView failed", e)
            LogManager.error("FLIR: Failed to inflate layout: ${e.message}")
            null
        }
    }
    
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        Log.d(TAG, "onViewCreated called")
        
        try {
            thermalImageView = view.findViewById(R.id.thermalImageView)
            statusText = view.findViewById(R.id.statusText)
            
            Log.d(TAG, "Views found: imageView=${thermalImageView != null}, statusText=${statusText != null}")
            LogManager.info("FLIR: Views initialized")
            
            updateStatus("Initializing FLIR SDK...")
            
            // Try to initialize frame processor
            try {
                Log.d(TAG, "Attempting to create FrameProcessor...")
                // Use ThermalLinearFlux14BitImage - raw thermal data without MSX blending
                frameProcessor = FrameProcessor(
                    requireContext(),
                    this,
                    EnumSet.of(RenderedImage.ImageType.ThermalLinearFlux14BitImage)
                )
                sdkInitialized = true
                updateStatus("SDK initialized - Ready for device")
                Log.d(TAG, "FrameProcessor created successfully!")
                LogManager.success("FLIR: SDK initialized successfully")
            } catch (e: UnsatisfiedLinkError) {
                sdkInitialized = false
                val errorMsg = "Native library error: ${e.message}"
                updateStatus("Error: Native libraries not loaded\n${e.message?.substring(0, minOf(100, e.message?.length ?: 0))}")
                Log.e(TAG, errorMsg, e)
                LogManager.error("FLIR: $errorMsg")
            } catch (e: NoClassDefFoundError) {
                sdkInitialized = false
                val errorMsg = "SDK class not found: ${e.message}"
                updateStatus("Error: FLIR SDK classes missing\n${e.message}")
                Log.e(TAG, errorMsg, e)
                LogManager.error("FLIR: $errorMsg")
            } catch (e: Exception) {
                sdkInitialized = false
                val errorMsg = "SDK initialization failed: ${e.message}"
                updateStatus("Error: ${e.message}")
                Log.e(TAG, errorMsg, e)
                LogManager.error("FLIR: $errorMsg")
                e.printStackTrace()
            }
        } catch (e: Exception) {
            Log.e(TAG, "onViewCreated failed", e)
            LogManager.error("FLIR: View creation failed: ${e.message}")
            e.printStackTrace()
        }
    }
    
    override fun onResume() {
        super.onResume()
        Log.d(TAG, "onResume called, sdkInitialized=$sdkInitialized")
        
        if (!sdkInitialized) {
            updateStatus("SDK not initialized - cannot start discovery")
            LogManager.info("FLIR: SDK not initialized, skipping discovery")
            return
        }
        
        try {
            Log.d(TAG, "Starting device discovery...")
            Device.startDiscovery(requireContext(), this)
            updateStatus("Searching for FLIR ONE camera...")
            LogManager.info("FLIR: Device discovery started")
        } catch (e: Exception) {
            val errorMsg = "Failed to start device discovery: ${e.message}"
            updateStatus("Error: $errorMsg")
            Log.e(TAG, errorMsg, e)
            LogManager.error("FLIR: $errorMsg")
            e.printStackTrace()
        }
    }
    
    override fun onPause() {
        super.onPause()
        Log.d(TAG, "onPause called")
        
        if (!sdkInitialized) {
            return
        }
        
        try {
            Device.stopDiscovery()
            updateStatus("Paused")
            LogManager.info("FLIR: Device discovery stopped")
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping discovery", e)
            LogManager.error("FLIR: Error stopping discovery: ${e.message}")
        }
    }
    
    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "onDestroy called")
        flirDevice = null
        frameProcessor = null
    }
    
    // Device.Delegate callbacks
    
    override fun onDeviceConnected(device: Device) {
        Log.d(TAG, "onDeviceConnected")
        
        try {
            flirDevice = device
            updateStatus("Connected! Starting video stream...")
            LogManager.success("FLIR: Camera connected")
            
            // Start receiving thermal frames
            Log.d(TAG, "Starting frame stream...")
            device.startFrameStream(object : Device.StreamDelegate {
                override fun onFrameReceived(frame: Frame) {
                    try {
                        frameProcessor?.processFrame(frame)
                    } catch (e: Exception) {
                        Log.e(TAG, "Error processing frame", e)
                        LogManager.error("FLIR: Frame processing error: ${e.message}")
                    }
                }
            })
            
            updateStatus("Streaming thermal video...")
            LogManager.success("FLIR: Video stream started")
            
        } catch (e: Exception) {
            val errorMsg = "Error starting frame stream: ${e.message}"
            updateStatus("Error: $errorMsg")
            Log.e(TAG, errorMsg, e)
            LogManager.error("FLIR: $errorMsg")
            e.printStackTrace()
        }
    }
    
    override fun onDeviceDisconnected(device: Device) {
        Log.d(TAG, "onDeviceDisconnected")
        flirDevice = null
        updateStatus("Camera disconnected")
        LogManager.info("FLIR: Camera disconnected")
    }
    
    override fun onTuningStateChanged(tuningState: Device.TuningState) {
        try {
            Log.d(TAG, "onTuningStateChanged: $tuningState")
            if (tuningState == Device.TuningState.InProgress) {
                updateStatus("Calibrating (shutter click)...")
                LogManager.info("FLIR: Camera calibrating...")
            } else {
                updateStatus("Streaming thermal video...")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error in tuning state change", e)
        }
    }
    
    override fun onAutomaticTuningChanged(automatic: Boolean) {
        Log.d(TAG, "onAutomaticTuningChanged: automatic=$automatic")
    }
    
    // FrameProcessor.Delegate callback
    
    override fun onFrameProcessed(renderedImage: RenderedImage) {
        try {
            val width = renderedImage.width()
            val height = renderedImage.height()
            
            Log.d(TAG, "Frame received: ${width}x${height}")
            
            // ThermalLinearFlux14BitImage - 14-bit thermal flux values (2 bytes per pixel)
            val pixelData = renderedImage.pixelData()
            val pixels = IntArray(width * height)
            
            // Find min/max for auto-scaling
            var minVal = 65535
            var maxVal = 0
            
            for (i in 0 until (width * height)) {
                val value = ((pixelData[i * 2].toInt() and 0xFF) or 
                            ((pixelData[i * 2 + 1].toInt() and 0xFF) shl 8)) and 0x3FFF  // 14-bit
                if (value < minVal) minVal = value
                if (value > maxVal) maxVal = value
            }
            
            val range = maxVal - minVal
            if (range == 0) return  // Avoid division by zero
            
            // Convert to color-mapped image (Iron palette)
            for (i in 0 until (width * height)) {
                val value = ((pixelData[i * 2].toInt() and 0xFF) or 
                            ((pixelData[i * 2 + 1].toInt() and 0xFF) shl 8)) and 0x3FFF
                
                // Normalize to 0-255
                val normalized = ((value - minVal) * 255 / range).coerceIn(0, 255)
                
                // Iron color palette (common thermal imaging palette)
                val r: Int
                val g: Int
                val b: Int
                
                when {
                    normalized < 64 -> {
                        // Black to Purple
                        r = (normalized * 2).coerceIn(0, 255)
                        g = 0
                        b = (normalized * 4).coerceIn(0, 255)
                    }
                    normalized < 128 -> {
                        // Purple to Red
                        val adj = normalized - 64
                        r = 128 + adj * 2
                        g = 0
                        b = 255 - adj * 4
                    }
                    normalized < 192 -> {
                        // Red to Yellow
                        val adj = normalized - 128
                        r = 255
                        g = adj * 4
                        b = 0
                    }
                    else -> {
                        // Yellow to White
                        val adj = normalized - 192
                        r = 255
                        g = 255
                        b = adj * 4
                    }
                }
                
                pixels[i] = (255 shl 24) or (r.coerceIn(0, 255) shl 16) or (g.coerceIn(0, 255) shl 8) or b.coerceIn(0, 255)
            }
            
            val imageBitmap = Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)
            
            // Update UI on main thread
            activity?.runOnUiThread {
                thermalImageView?.setImageBitmap(imageBitmap)
                updateStatus("Streaming (range: $minVal-$maxVal)")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error rendering frame", e)
            LogManager.error("FLIR: Frame rendering error: ${e.message}")
            e.printStackTrace()
        }
    }
    
    private fun updateStatus(message: String) {
        activity?.runOnUiThread {
            statusText?.text = message
            Log.d(TAG, "Status: $message")
        }
    }
}
