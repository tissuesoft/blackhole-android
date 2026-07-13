package com.blackhole.screensaver.idle

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.view.accessibility.AccessibilityEvent
import com.blackhole.screensaver.BlackholeApp

/**
 * Global input sensor. Updates last-input timestamp used by the foreground service.
 */
class IdleAccessibilityService : AccessibilityService() {

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
    }
}
