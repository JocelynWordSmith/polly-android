package com.robotics.polly

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.IBinder
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

    private var bridgeService: BridgeService? = null
    private var serviceBound = false

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as BridgeService.BridgeBinder
            bridgeService = binder.getService()
            serviceBound = true
            LogManager.info("Bound to BridgeService")
            // Start camera with activity lifecycle
            binder.getService().startCamera(this@MainActivity)
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            bridgeService = null
            serviceBound = false
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        toolbar = findViewById(R.id.toolbar)
        setSupportActionBar(toolbar)

        viewPager = findViewById(R.id.viewPager)
        bottomNav = findViewById(R.id.bottomNav)

        // Setup ViewPager — pre-load all pages so listeners register early
        val adapter = ViewPagerAdapter(this)
        viewPager.adapter = adapter
        viewPager.offscreenPageLimit = 2

        // Sync ViewPager with BottomNav
        viewPager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                bottomNav.menu.getItem(position).isChecked = true
            }
        })

        bottomNav.setOnItemSelectedListener { item ->
            when (item.itemId) {
                R.id.nav_bridge -> viewPager.currentItem = 0
                R.id.nav_devices -> viewPager.currentItem = 1
                R.id.nav_logs -> viewPager.currentItem = 2
            }
            true
        }

        // Start and bind to BridgeService
        val serviceIntent = Intent(this, BridgeService::class.java)
        startForegroundService(serviceIntent)
        bindService(serviceIntent, serviceConnection, Context.BIND_AUTO_CREATE)

        LogManager.info("App started")
    }

    fun getBridgeService(): BridgeService? = bridgeService

    // Keep getUsbSerial() for backward compatibility with fragments
    fun getUsbSerial(): UsbSerialManager? {
        return bridgeService?.getArduinoBridge()?.getUsbSerial()
    }

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
        if (serviceBound) {
            unbindService(serviceConnection)
            serviceBound = false
        }
        // Don't stop the service here — it should survive activity restarts
    }
}
