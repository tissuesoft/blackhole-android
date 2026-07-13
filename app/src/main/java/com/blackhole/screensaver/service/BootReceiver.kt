package com.blackhole.screensaver.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.blackhole.screensaver.prefs.AppPrefs
import com.blackhole.screensaver.prefs.PermissionHelper

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != Intent.ACTION_BOOT_COMPLETED) return
        if (!AppPrefs.enabled) return
        if (!PermissionHelper.canDrawOverlays(context)) return
        if (!PermissionHelper.isAccessibilityEnabled(context)) return
        BlackholeForegroundService.start(context)
    }
}
