package com.blackhole.screensaver.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import com.blackhole.screensaver.BlackholeApp
import com.blackhole.screensaver.R
import com.blackhole.screensaver.prefs.AppPrefs
import com.blackhole.screensaver.prefs.PermissionHelper
import com.blackhole.screensaver.service.BlackholeForegroundService
import com.blackhole.screensaver.service.FeatureController
import com.blackhole.screensaver.ui.MainActivity

class BlackholeWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        appWidgetIds.forEach { id ->
            appWidgetManager.updateAppWidget(id, buildViews(context))
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        when (intent.action) {
            BlackholeApp.ACTION_WIDGET_TOGGLE -> {
                val turningOn = !AppPrefs.enabled
                if (turningOn) {
                    if (!PermissionHelper.allRequiredGranted(context) && !PermissionHelper.canDrawOverlays(context)) {
                        context.startActivity(
                            Intent(context, MainActivity::class.java)
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        )
                        return
                    }
                    if (!PermissionHelper.canDrawOverlays(context) ||
                        !PermissionHelper.isAccessibilityEnabled(context) ||
                        !PermissionHelper.notificationsAllowed(context)
                    ) {
                        context.startActivity(
                            Intent(context, MainActivity::class.java)
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        )
                        return
                    }
                    // MediaProjection tokens are single-use; if capture isn't live, open the app.
                    if (!BlackholeForegroundService.hasActiveCapture) {
                        context.startActivity(
                            Intent(context, MainActivity::class.java)
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                .putExtra(MainActivity.EXTRA_REQUEST_ENABLE, true)
                        )
                        return
                    }
                    FeatureController.setEnabled(context, true)
                } else {
                    FeatureController.setEnabled(context, false)
                }
                requestUpdate(context)
            }
            BlackholeApp.ACTION_STATE_CHANGED,
            AppWidgetManager.ACTION_APPWIDGET_UPDATE -> requestUpdate(context)
        }
    }

    companion object {
        fun requestUpdate(context: Context) {
            val manager = AppWidgetManager.getInstance(context)
            val ids = manager.getAppWidgetIds(ComponentName(context, BlackholeWidgetProvider::class.java))
            if (ids.isEmpty()) return
            val views = buildViews(context)
            ids.forEach { manager.updateAppWidget(it, views) }
        }

        private fun buildViews(context: Context): RemoteViews {
            val on = AppPrefs.enabled
            val views = RemoteViews(context.packageName, R.layout.widget_blackhole)
            views.setTextViewText(
                R.id.widgetState,
                context.getString(if (on) R.string.widget_on else R.string.widget_off)
            )
            views.setTextColor(
                R.id.widgetState,
                context.getColor(if (on) R.color.on else R.color.off)
            )
            val toggleIntent = Intent(context, BlackholeWidgetProvider::class.java).apply {
                action = BlackholeApp.ACTION_WIDGET_TOGGLE
            }
            val pi = PendingIntent.getBroadcast(
                context,
                1,
                toggleIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            views.setOnClickPendingIntent(R.id.widgetRoot, pi)
            return views
        }
    }
}
