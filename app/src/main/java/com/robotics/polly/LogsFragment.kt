package com.robotics.polly

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ScrollView
import android.widget.TextView
import androidx.fragment.app.Fragment

class LogsFragment : Fragment() {
    
    private lateinit var logText: TextView
    private lateinit var clearButton: Button
    private lateinit var scrollView: ScrollView
    private val handler = Handler(Looper.getMainLooper())
    
    private val updateRunnable = object : Runnable {
        override fun run() {
            updateLog()
            handler.postDelayed(this, 200) // Update every 200ms
        }
    }
    
    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val view = View(requireContext()).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }
        
        // Create layout programmatically
        val layout = android.widget.LinearLayout(requireContext()).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(16, 16, 16, 16)
            setBackgroundColor(0xFFF5F5F5.toInt())
        }
        
        // Header
        val header = android.widget.LinearLayout(requireContext()).apply {
            orientation = android.widget.LinearLayout.HORIZONTAL
            setPadding(0, 0, 0, 12)
        }
        
        val title = TextView(requireContext()).apply {
            text = "📋 Debug Logs"
            textSize = 20f
            setTypeface(null, android.graphics.Typeface.BOLD)
            layoutParams = android.widget.LinearLayout.LayoutParams(
                0,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                1f
            )
        }
        
        clearButton = Button(requireContext()).apply {
            text = "Clear"
            setOnClickListener {
                LogManager.clear()
                updateLog()
            }
        }
        
        header.addView(title)
        header.addView(clearButton)
        
        // Scrollable log area
        scrollView = ScrollView(requireContext()).apply {
            layoutParams = android.widget.LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
            setBackgroundColor(0xFF1E1E1E.toInt())
            setPadding(12, 12, 12, 12)
        }
        
        logText = TextView(requireContext()).apply {
            text = ""
            textSize = 10f
            typeface = android.graphics.Typeface.MONOSPACE
            setTextColor(0xFFFFFFFF.toInt())
        }
        
        scrollView.addView(logText)
        layout.addView(header)
        layout.addView(scrollView)
        
        (view as? ViewGroup)?.addView(layout) ?: return layout
        
        return layout
    }
    
    override fun onResume() {
        super.onResume()
        handler.post(updateRunnable)
        updateLog()
    }
    
    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(updateRunnable)
    }
    
    private fun updateLog() {
        logText.text = LogManager.getFormattedLogs()
        // Auto-scroll to bottom
        scrollView.post {
            scrollView.fullScroll(ScrollView.FOCUS_DOWN)
        }
    }
}
