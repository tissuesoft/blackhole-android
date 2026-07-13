package com.blackhole.screensaver.service

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.blackhole.screensaver.BlackholeApp
import com.blackhole.screensaver.R
import com.blackhole.screensaver.idle.IdleAccessibilityService
import com.blackhole.screensaver.idle.MediaPlaybackDetector
import com.blackhole.screensaver.overlay.OverlayController
import com.blackhole.screensaver.prefs.AppPrefs
import com.blackhole.screensaver.prefs.PermissionHelper
import com.blackhole.screensaver.ui.MainActivity

/**
 * Idle watch + blackhole overlay.
 * Screen frames come from Accessibility takeScreenshot (no MediaProjection),
 * so screen off/on does not kill the feature or require re-consent.
 */
class BlackholeForegroundService : Service() {

    private val mainHandler = Handler(Looper.getMainLooper())
    private var overlay: OverlayController? = null

    @Volatile
    private var pendingShow = false

    private val tickRunnable = object : Runnable {
        override fun run() {
            tick()
            mainHandler.postDelayed(this, TICK_MS)
        }
    }

    private val inputReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                BlackholeApp.ACTION_INPUT_DETECTED -> onUserInput()
                BlackholeApp.ACTION_IDLE_SECONDS_CHANGED -> Unit
            }
        }
    }

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                Intent.ACTION_SCREEN_OFF -> {
                    pendingShow = false
                    hideOverlay()
                }
                Intent.ACTION_SCREEN_ON,
                Intent.ACTION_USER_PRESENT -> {
                    // Waking the phone resets idle; feature stays armed — no re-consent.
                    IdleAccessibilityService.markInput()
                    pendingShow = false
                    hideOverlay()
                }
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        ContextCompat.registerReceiver(
            this,
            inputReceiver,
            IntentFilter().apply {
                addAction(BlackholeApp.ACTION_INPUT_DETECTED)
                addAction(BlackholeApp.ACTION_IDLE_SECONDS_CHANGED)
            },
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        ContextCompat.registerReceiver(
            this,
            screenReceiver,
            IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_OFF)
                addAction(Intent.ACTION_SCREEN_ON)
                addAction(Intent.ACTION_USER_PRESENT)
            },
            ContextCompat.RECEIVER_EXPORTED
        )
        overlay = OverlayController(this) {
            IdleAccessibilityService.markInput()
            onUserInput()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                AppPrefs.enabled = false
                pendingShow = false
                hideOverlay()
                stopForegroundSafe()
                stopSelf()
                broadcastState()
                return START_NOT_STICKY
            }
            ACTION_DISMISS_OVERLAY -> onUserInput()
            else -> {
                startAsForeground()
                if (AppPrefs.enabled) {
                    mainHandler.removeCallbacks(tickRunnable)
                    mainHandler.post(tickRunnable)
                } else {
                    pendingShow = false
                    hideOverlay()
                    stopForegroundSafe()
                    stopSelf()
                }
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        mainHandler.removeCallbacks(tickRunnable)
        pendingShow = false
        hideOverlay()
        try {
            unregisterReceiver(inputReceiver)
        } catch (_: Exception) {
        }
        try {
            unregisterReceiver(screenReceiver)
        } catch (_: Exception) {
        }
        if (instance === this) instance = null
        super.onDestroy()
    }

    private fun startAsForeground() {
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification: Notification = NotificationCompat.Builder(this, BlackholeApp.CHANNEL_ID)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(getString(R.string.notification_text))
            .setSmallIcon(R.drawable.ic_blackhole)
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun stopForegroundSafe() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                stopForeground(STOP_FOREGROUND_REMOVE)
            } else {
                @Suppress("DEPRECATION")
                stopForeground(true)
            }
        } catch (_: Exception) {
        }
    }

    private fun tick() {
        if (!AppPrefs.enabled) {
            hideOverlay()
            stopForegroundSafe()
            stopSelf()
            return
        }
        if (!PermissionHelper.canDrawOverlays(this)) {
            hideOverlay()
            return
        }
        if (!isScreenOn()) {
            hideOverlay()
            return
        }

        // Don't show (and dismiss if showing) while YouTube/music/video audio plays.
        if (MediaPlaybackDetector.isMediaPlaying(this)) {
            IdleAccessibilityService.markInput()
            pendingShow = false
            hideOverlay()
            return
        }

        val idleLimit = AppPrefs.idleSeconds * 1_000L
        val idle = IdleAccessibilityService.idleMillis()
        if (idle >= idleLimit) {
            showOverlay()
        }
    }

    private fun isScreenOn(): Boolean {
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        return pm.isInteractive
    }

    private fun showOverlay() {
        val o = overlay ?: return
        if (o.isShowing || pendingShow) return
        if (IdleAccessibilityService.instance == null) return
        if (MediaPlaybackDetector.isMediaPlaying(this)) return

        pendingShow = true
        IdleAccessibilityService.captureScreen { bmp ->
            mainHandler.post {
                if (!pendingShow) {
                    bmp?.recycle()
                    return@post
                }
                pendingShow = false
                if (!AppPrefs.enabled ||
                    !isScreenOn() ||
                    overlay?.isShowing == true ||
                    MediaPlaybackDetector.isMediaPlaying(this)
                ) {
                    bmp?.recycle()
                    return@post
                }
                if (bmp == null || bmp.isRecycled) return@post
                presentOverlay(bmp)
            }
        }
    }

    private fun presentOverlay(seed: Bitmap) {
        val o = overlay ?: run {
            if (!seed.isRecycled) seed.recycle()
            return
        }
        if (o.isShowing) {
            if (!seed.isRecycled) seed.recycle()
            return
        }
        o.renderer.submitFrame(seed)
        o.show()
    }

    private fun hideOverlay() {
        overlay?.hide()
    }

    private fun onUserInput() {
        IdleAccessibilityService.markInput()
        pendingShow = false
        hideOverlay()
    }

    private fun broadcastState() {
        sendBroadcast(Intent(BlackholeApp.ACTION_STATE_CHANGED).setPackage(packageName))
    }

    companion object {
        const val ACTION_START = "com.blackhole.screensaver.action.START"
        const val ACTION_STOP = "com.blackhole.screensaver.action.STOP"
        const val ACTION_DISMISS_OVERLAY = "com.blackhole.screensaver.action.DISMISS"
        private const val NOTIFICATION_ID = 42
        private const val TICK_MS = 1000L

        @Volatile
        var instance: BlackholeForegroundService? = null
            private set

        fun start(context: Context) {
            val intent = Intent(context, BlackholeForegroundService::class.java).apply {
                action = ACTION_START
            }
            ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            val intent = Intent(context, BlackholeForegroundService::class.java).apply {
                action = ACTION_STOP
            }
            try {
                ContextCompat.startForegroundService(context, intent)
            } catch (_: Exception) {
                context.stopService(Intent(context, BlackholeForegroundService::class.java))
            }
        }
    }
}
