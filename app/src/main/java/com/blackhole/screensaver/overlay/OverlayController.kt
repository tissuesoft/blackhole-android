package com.blackhole.screensaver.overlay

import android.content.Context
import android.graphics.PixelFormat
import android.opengl.GLSurfaceView
import android.os.Build
import android.view.Gravity
import android.view.MotionEvent
import android.view.WindowManager
import com.blackhole.screensaver.gl.BlackholeRenderer

class OverlayController(
    private val context: Context,
    private val onUserTouch: () -> Unit
) {
    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private var surfaceView: GLSurfaceView? = null
    val renderer = BlackholeRenderer()

    val isShowing: Boolean get() = surfaceView != null

    fun show() {
        if (surfaceView != null) return

        val view = GLSurfaceView(context).apply {
            // Transparent outside the BH so the real screen stays 1:1 underneath.
            setZOrderOnTop(true)
            holder.setFormat(PixelFormat.TRANSLUCENT)
            setEGLConfigChooser(8, 8, 8, 8, 16, 0)
            setEGLContextClientVersion(2)
            setRenderer(renderer)
            renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY
            setOnTouchListener { _, event ->
                if (event.action == MotionEvent.ACTION_DOWN) {
                    onUserTouch()
                    true
                } else {
                    true
                }
            }
            keepScreenOn = true
        }

        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_SYSTEM_ALERT
        }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            type,
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_FULLSCREEN or
                WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
                // Draw under system bars / cutouts so the overlay is truly edge-to-edge.
                setFitInsetsTypes(0)
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }

        windowManager.addView(view, params)
        surfaceView = view
    }

    fun hide() {
        val view = surfaceView ?: return
        try {
            windowManager.removeView(view)
        } catch (_: Exception) {
        }
        surfaceView = null
        renderer.clearPendingFrames()
    }
}
