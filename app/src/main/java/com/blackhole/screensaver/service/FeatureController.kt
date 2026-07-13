package com.blackhole.screensaver.service

import android.content.Context
import android.content.Intent
import com.blackhole.screensaver.BlackholeApp
import com.blackhole.screensaver.prefs.AppPrefs
import com.blackhole.screensaver.prefs.PermissionHelper
import com.blackhole.screensaver.widget.BlackholeWidgetProvider

/**
 * Shared enable/disable path for Activity + App Widget.
 * No MediaProjection — accessibility screenshots power the overlay.
 */
object FeatureController {

    fun setEnabled(context: Context, enabled: Boolean): Boolean {
        if (enabled) {
            if (!PermissionHelper.canDrawOverlays(context) ||
                !PermissionHelper.notificationsAllowed(context) ||
                !PermissionHelper.isAccessibilityEnabled(context)
            ) {
                return false
            }
            AppPrefs.enabled = true
            BlackholeForegroundService.start(context)
        } else {
            AppPrefs.enabled = false
            BlackholeForegroundService.stop(context)
        }
        notifyWidgets(context)
        return true
    }

    fun notifyWidgets(context: Context) {
        context.sendBroadcast(
            Intent(BlackholeApp.ACTION_STATE_CHANGED).setPackage(context.packageName)
        )
        BlackholeWidgetProvider.requestUpdate(context)
    }
}
