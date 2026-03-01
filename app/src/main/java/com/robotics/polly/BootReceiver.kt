package com.robotics.polly

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            Log.d(TAG, "Boot completed, starting BridgeService")
            context.startForegroundService(Intent(context, BridgeService::class.java))
        }
    }

    companion object {
        private const val TAG = "BootReceiver"
    }
}
