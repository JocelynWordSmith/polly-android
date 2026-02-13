package com.robotics.polly

import android.content.Intent
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import androidx.viewpager2.widget.ViewPager2
import com.google.android.material.bottomnavigation.BottomNavigationView

class MainActivity : AppCompatActivity() {
    
    private lateinit var viewPager: ViewPager2
    private lateinit var bottomNav: BottomNavigationView
    private lateinit var toolbar: Toolbar
    private lateinit var usbSerial: UsbSerialManager
    private var httpServer: PollyServer? = null
    
    private val SERVER_PORT = 8080
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        
        toolbar = findViewById(R.id.toolbar)
        setSupportActionBar(toolbar)
        
        viewPager = findViewById(R.id.viewPager)
        bottomNav = findViewById(R.id.bottomNav)
        
        // Setup ViewPager
        val adapter = ViewPagerAdapter(this)
        viewPager.adapter = adapter
        
        // Sync ViewPager with BottomNav
        viewPager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                bottomNav.menu.getItem(position).isChecked = true
            }
        })
        
        bottomNav.setOnItemSelectedListener { item ->
            when (item.itemId) {
                R.id.nav_polly -> viewPager.currentItem = 0
                R.id.nav_arduino -> viewPager.currentItem = 1
                R.id.nav_lidar -> viewPager.currentItem = 2
                R.id.nav_flir -> viewPager.currentItem = 3
                R.id.nav_logs -> viewPager.currentItem = 4
            }
            true
        }
        
        // Start HTTP server FIRST so it exists when USB connects
        startServer()
        
        // Initialize USB Serial
        usbSerial = UsbSerialManager(this)
        usbSerial.onConnectionChanged = { connected, message ->
            runOnUiThread {
                if (connected) {
                    LogManager.success("USB: $message")
                    httpServer?.usbConnected = true
                } else {
                    LogManager.info("USB: $message")
                    httpServer?.usbConnected = false
                }
            }
        }
        usbSerial.onDataReceived = { data ->
            // Don't log every RX - too spammy
            // Data is displayed in the Arduino Sensors UI
        }
        usbSerial.initialize()
        
        LogManager.info("App started")
    }
    
    private fun startServer() {
        try {
            httpServer = PollyServer(SERVER_PORT)
            httpServer?.onMotorCommand = { action, speed ->
                handleMotorCommand(action, speed)
            }
            httpServer?.start()
            LogManager.success("HTTP server started on port $SERVER_PORT")
        } catch (e: Exception) {
            LogManager.error("HTTP server failed: ${e.message}")
        }
    }
    
    private fun handleMotorCommand(action: String, speed: Int) {
        val command = when (action) {
            "forward" -> "{\"N\":2,\"D1\":$speed}"
            "backward" -> "{\"N\":3,\"D1\":$speed}"
            "left" -> "{\"N\":4,\"D1\":$speed}"
            "right" -> "{\"N\":5,\"D1\":$speed}"
            "stop" -> "{\"N\":6}"
            else -> return
        }
        
        LogManager.tx(command)
        usbSerial.sendCommand(command)
    }
    
    fun getUsbSerial(): UsbSerialManager = usbSerial
    
    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.toolbar_menu, menu)
        return true
    }
    
    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_settings -> {
                showSettingsDialog()
                true
            }
            R.id.action_restart -> {
                restartApp()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }
    
    private fun showSettingsDialog() {
        val intent = Intent(this, SettingsActivity::class.java)
        startActivity(intent)
    }
    
    private fun restartApp() {
        LogManager.info("Restarting app...")
        val intent = Intent(this, MainActivity::class.java)
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        startActivity(intent)
        finish()
        Runtime.getRuntime().exit(0)
    }
    
    override fun onDestroy() {
        super.onDestroy()
        httpServer?.stop()
        usbSerial.cleanup()
    }
}
