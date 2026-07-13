package com.blackhole.screensaver.service

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.blackhole.screensaver.BlackholeApp
import com.blackhole.screensaver.R
import com.blackhole.screensaver.capture.ScreenCapturer
import com.blackhole.screensaver.idle.IdleAccessibilityService
import com.blackhole.screensaver.overlay.OverlayController
import com.blackhole.screensaver.prefs.AppPrefs
import com.blackhole.screensaver.prefs.PermissionHelper
import com.blackhole.screensaver.ui.MainActivity

/**
 * Keeps MediaProjection + idle watch alive after the Activity is closed.
 */
class BlackholeForegroundService : Service() {

    private val mainHandler = Handler(Looper.getMainLooper())
    private var capturer: ScreenCapturer? = null
    private var overlay: OverlayController? = null

    private var resultCode: Int = 0
    private var resultData: Intent? = null

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
                BlackholeApp.ACTION_IDLE_MINUTES_CHANGED -> Unit // next tick picks up prefs
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        val filter = IntentFilter().apply {
            addAction(BlackholeApp.ACTION_INPUT_DETECTED)
            addAction(BlackholeApp.ACTION_IDLE_MINUTES_CHANGED)
        }
        ContextCompat.registerReceiver(
            this,
            inputReceiver,
            filter,
            ContextCompat.RECEIVER_NOT_EXPORTED
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
                hideOverlay()
                stopCapture()
                stopSelf()
                broadcastState()
                return START_NOT_STICKY
            }
            ACTION_DISMISS_OVERLAY -> {
                onUserInput()
            }
            else -> {
                if (intent?.hasExtra(EXTRA_RESULT_CODE) == true) {
                    resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0)
                    resultData = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        intent.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
                    } else {
                        @Suppress("DEPRECATION")
                        intent.getParcelableExtra(EXTRA_RESULT_DATA)
                    }
                    AppPrefs.captureGranted = true
                }
                startAsForeground(needCapturePrompt = resultData == null)
                if (AppPrefs.enabled) {
                    ensureCapture()
                    mainHandler.removeCallbacks(tickRunnable)
                    mainHandler.post(tickRunnable)
                } else {
                    hideOverlay()
                    stopCapture()
                    stopSelf()
                }
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        mainHandler.removeCallbacks(tickRunnable)
        hideOverlay()
        stopCapture()
        try {
            unregisterReceiver(inputReceiver)
        } catch (_: Exception) {
        }
        if (instance === this) instance = null
        super.onDestroy()
    }

    private fun startAsForeground(needCapturePrompt: Boolean) {
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val text = if (needCapturePrompt) {
            getString(R.string.notification_need_capture)
        } else {
            getString(R.string.notification_text)
        }
        val notification: Notification = NotificationCompat.Builder(this, BlackholeApp.CHANNEL_ID)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_blackhole)
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun ensureCapture() {
        if (capturer?.isRunning == true) return
        val data = resultData ?: return
        val cap = ScreenCapturer(this) { bmp ->
            mainHandler.post {
                val o = overlay
                if (o != null && o.isShowing) {
                    o.renderer.submitFrame(bmp)
                } else {
                    lastFrame?.let { old -> if (old !== bmp && !old.isRecycled) old.recycle() }
                    lastFrame = bmp
                }
            }
        }
        if (cap.start(resultCode, data)) {
            capturer = cap
            // While waiting idle, still capture into lastFrame; when overlay shows, emit live
            // but pause emit briefly at show to avoid immediate self-capture feedback.
            cap.emitFrames = true
            startAsForeground(needCapturePrompt = false)
        } else {
            capturer = null
            startAsForeground(needCapturePrompt = true)
        }
    }

    @Volatile
    private var lastFrame: android.graphics.Bitmap? = null

    private fun tick() {
        if (!AppPrefs.enabled) {
            hideOverlay()
            stopCapture()
            stopSelf()
            return
        }
        if (!PermissionHelper.canDrawOverlays(this)) {
            hideOverlay()
            return
        }
        ensureCapture()

        val idleLimit = AppPrefs.idleMinutes * 60_000L
        val idle = IdleAccessibilityService.idleMillis()
        if (idle >= idleLimit) {
            showOverlay()
        }
    }

    private fun showOverlay() {
        val o = overlay ?: return
        if (o.isShowing) return
        // Seed with last captured frame (realtime screen at idle trigger).
        lastFrame?.let { seed ->
            if (!seed.isRecycled) {
                o.renderer.submitFrame(seed.copy(seed.config ?: android.graphics.Bitmap.Config.ARGB_8888, false))
            }
        }
        // Pause frame emit while overlay is visible — MediaProjection includes our
        // overlay window, which would otherwise create recursive feedback.
        capturer?.emitFrames = false
        o.show()
    }

    private fun hideOverlay() {
        capturer?.emitFrames = true
        overlay?.hide()
    }

    private fun onUserInput() {
        IdleAccessibilityService.markInput()
        hideOverlay()
    }

    private fun stopCapture() {
        capturer?.stop()
        capturer = null
        lastFrame?.let { if (!it.isRecycled) it.recycle() }
        lastFrame = null
    }

    private fun broadcastState() {
        sendBroadcast(Intent(BlackholeApp.ACTION_STATE_CHANGED).setPackage(packageName))
    }

    companion object {
        const val ACTION_START = "com.blackhole.screensaver.action.START"
        const val ACTION_STOP = "com.blackhole.screensaver.action.STOP"
        const val ACTION_DISMISS_OVERLAY = "com.blackhole.screensaver.action.DISMISS"
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_RESULT_DATA = "result_data"
        private const val NOTIFICATION_ID = 42
        private const val TICK_MS = 1000L

        @Volatile
        var instance: BlackholeForegroundService? = null
            private set

        val hasActiveCapture: Boolean
            get() = instance?.capturer?.isRunning == true

        fun start(context: Context, resultCode: Int? = null, data: Intent? = null) {
            val intent = Intent(context, BlackholeForegroundService::class.java).apply {
                action = ACTION_START
                if (resultCode != null && data != null) {
                    putExtra(EXTRA_RESULT_CODE, resultCode)
                    putExtra(EXTRA_RESULT_DATA, data)
                }
            }
            ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            val intent = Intent(context, BlackholeForegroundService::class.java).apply {
                action = ACTION_STOP
            }
            // If service not running, start briefly to handle STOP, or just stopService.
            try {
                ContextCompat.startForegroundService(context, intent)
            } catch (_: Exception) {
                context.stopService(Intent(context, BlackholeForegroundService::class.java))
            }
        }
    }
}
