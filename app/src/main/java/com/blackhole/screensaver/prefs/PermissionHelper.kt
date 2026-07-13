package com.blackhole.screensaver.prefs

import android.accessibilityservice.AccessibilityServiceInfo
import android.app.AppOpsManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.view.accessibility.AccessibilityManager
import androidx.core.app.NotificationManagerCompat
import com.blackhole.screensaver.idle.IdleAccessibilityService

object PermissionHelper {

    fun canDrawOverlays(context: Context): Boolean =
        Settings.canDrawOverlays(context)

    fun notificationsAllowed(context: Context): Boolean =
        NotificationManagerCompat.from(context).areNotificationsEnabled()

    /**
     * Robust check: Settings.Secure first (survives brief service reconnect after
     * screen on), then live instance, then AccessibilityManager list.
     */
    fun isAccessibilityEnabled(context: Context): Boolean {
        if (IdleAccessibilityService.instance != null) return true

        val expected = ComponentName(context, IdleAccessibilityService::class.java)
        val secure = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ).orEmpty()
        if (secure.isNotEmpty()) {
            val match = secure.split(':').any { raw ->
                val cn = ComponentName.unflattenFromString(raw.trim())
                cn != null && cn.packageName == expected.packageName &&
                    (cn.className == expected.className ||
                        cn.className.endsWith(expected.className) ||
                        expected.className.endsWith(cn.className.removePrefix(".")))
            }
            if (match) return true
        }

        val am = context.getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager
        val enabled = am.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
        val target = IdleAccessibilityService::class.java.name
        return enabled.any { info ->
            val si = info.resolveInfo?.serviceInfo ?: return@any false
            if (si.packageName != context.packageName) return@any false
            si.name == target || si.name.endsWith(".IdleAccessibilityService")
        }
    }

    fun hasCaptureConsent(): Boolean = AppPrefs.captureGranted

    fun allRequiredGranted(context: Context): Boolean =
        canDrawOverlays(context) &&
            notificationsAllowed(context) &&
            isAccessibilityEnabled(context)

    fun overlaySettingsIntent(context: Context): Intent =
        Intent(
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            Uri.parse("package:${context.packageName}")
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    fun accessibilitySettingsIntent(): Intent =
        Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    fun appNotificationSettingsIntent(context: Context): Intent {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
                putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        } else {
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.parse("package:${context.packageName}")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        }
    }

    /** Best-effort check that MediaProjection ops are still usable. */
    fun isMediaProjectionOpAllowed(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            return AppPrefs.captureGranted
        }
        val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        val mode = appOps.unsafeCheckOpNoThrow(
            "android:project_media",
            android.os.Process.myUid(),
            context.packageName
        )
        return mode == AppOpsManager.MODE_ALLOWED || AppPrefs.captureGranted
    }
}
