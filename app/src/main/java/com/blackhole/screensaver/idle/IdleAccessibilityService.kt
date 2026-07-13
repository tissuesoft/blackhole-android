package com.blackhole.screensaver.idle

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.graphics.Bitmap
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import com.blackhole.screensaver.BlackholeApp
import java.util.concurrent.Executors

/**
 * Global input sensor + screen snapshots for the blackhole overlay.
 * Uses Accessibility takeScreenshot so capture survives screen off/on
 * without MediaProjection consent dialogs.
 */
class IdleAccessibilityService : AccessibilityService() {

    private val mainHandler = Handler(Looper.getMainLooper())
    private val shotExecutor = Executors.newSingleThreadExecutor()

    override fun onServiceConnected() {
        instance = this
        notifyInput()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        val pkg = event.packageName?.toString()
        if (pkg == packageName) return
        when (event.eventType) {
            AccessibilityEvent.TYPE_VIEW_CLICKED,
            AccessibilityEvent.TYPE_VIEW_LONG_CLICKED,
            AccessibilityEvent.TYPE_VIEW_SCROLLED,
            AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED,
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> notifyInput()
        }
    }

    override fun onInterrupt() = Unit

    override fun onDestroy() {
        if (instance === this) instance = null
        super.onDestroy()
    }

    /**
     * One-shot screenshot of the default display as a software ARGB_8888 bitmap.
     */
    fun requestScreenshot(callback: (Bitmap?) -> Unit) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            mainHandler.post { callback(null) }
            return
        }
        takeScreenshot(
            Display.DEFAULT_DISPLAY,
            shotExecutor,
            object : TakeScreenshotCallback {
                override fun onSuccess(screenshot: ScreenshotResult) {
                    var software: Bitmap? = null
                    try {
                        val hw = Bitmap.wrapHardwareBuffer(
                            screenshot.hardwareBuffer,
                            screenshot.colorSpace
                        )
                        software = hw?.copy(Bitmap.Config.ARGB_8888, false)
                        hw?.recycle()
                    } catch (_: Exception) {
                        software = null
                    } finally {
                        try {
                            screenshot.hardwareBuffer.close()
                        } catch (_: Exception) {
                        }
                    }
                    val result = software
                    mainHandler.post { callback(result) }
                }

                override fun onFailure(errorCode: Int) {
                    mainHandler.post { callback(null) }
                }
            }
        )
    }

    private fun notifyInput() {
        lastInputElapsedRealtime = android.os.SystemClock.elapsedRealtime()
        sendBroadcast(Intent(BlackholeApp.ACTION_INPUT_DETECTED).setPackage(packageName))
    }

    companion object {
        @Volatile
        var instance: IdleAccessibilityService? = null
            private set

        @Volatile
        var lastInputElapsedRealtime: Long = android.os.SystemClock.elapsedRealtime()

        fun markInput() {
            lastInputElapsedRealtime = android.os.SystemClock.elapsedRealtime()
        }

        fun idleMillis(): Long =
            android.os.SystemClock.elapsedRealtime() - lastInputElapsedRealtime

        fun captureScreen(callback: (Bitmap?) -> Unit) {
            val svc = instance
            if (svc == null) {
                callback(null)
                return
            }
            svc.requestScreenshot(callback)
        }
    }
}
