package com.blackhole.screensaver.capture

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Handler
import android.os.HandlerThread
import android.util.DisplayMetrics
import android.view.WindowManager
import java.nio.ByteBuffer

/**
 * MediaProjection → VirtualDisplay → ImageReader → Bitmap frames.
 * No persistence; realtime only. Secure/DRM content arrives as black.
 *
 * Screen off / system policy often stops MediaProjection — [onStopped] is invoked then.
 */
class ScreenCapturer(
    private val context: Context,
    private val onFrame: (Bitmap) -> Unit,
    private val onStopped: (() -> Unit)? = null
) {
    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var thread: HandlerThread? = null
    private var handler: Handler? = null

    private var width = 0
    private var height = 0
    private var density = 0

    @Volatile
    private var running = false

    /** When true, still capture but do not emit frames (freeze last for overlay). */
    @Volatile
    var emitFrames: Boolean = true

    fun start(resultCode: Int, data: Intent): Boolean {
        stop(notifyStopped = false)
        val mpm = context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val projection = mpm.getMediaProjection(resultCode, data) ?: return false

        val metrics = DisplayMetrics()
        val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        @Suppress("DEPRECATION")
        wm.defaultDisplay.getRealMetrics(metrics)
        // Match display size 1:1 so the BH neighborhood does not look zoomed/stretched.
        // Even width/height required by some VirtualDisplay / ImageReader paths.
        width = metrics.widthPixels.coerceAtLeast(2) and 1.inv()
        height = metrics.heightPixels.coerceAtLeast(2) and 1.inv()
        density = metrics.densityDpi

        thread = HandlerThread("ScreenCapturer").also { it.start() }
        handler = Handler(thread!!.looper)

        projection.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                // System stopped projection (common after screen off). Clean up without
                // calling projection.stop() again, then notify the service.
                releaseResources(stopProjection = false)
                onStopped?.invoke()
            }
        }, handler)

        imageReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 3).also { reader ->
            reader.setOnImageAvailableListener({ r ->
                val image = r.acquireLatestImage() ?: return@setOnImageAvailableListener
                try {
                    if (emitFrames && running) {
                        imageToBitmap(image)?.let(onFrame)
                    }
                } finally {
                    image.close()
                }
            }, handler)
        }

        virtualDisplay = projection.createVirtualDisplay(
            "BlackholeCapture",
            width,
            height,
            density,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader!!.surface,
            null,
            handler
        )

        mediaProjection = projection
        running = true
        return true
    }

    fun stop(notifyStopped: Boolean = false) {
        val wasRunning = running
        releaseResources(stopProjection = true)
        if (notifyStopped && wasRunning) {
            onStopped?.invoke()
        }
    }

    private fun releaseResources(stopProjection: Boolean) {
        running = false
        try {
            virtualDisplay?.release()
        } catch (_: Exception) {
        }
        virtualDisplay = null
        try {
            imageReader?.close()
        } catch (_: Exception) {
        }
        imageReader = null
        val projection = mediaProjection
        mediaProjection = null
        if (stopProjection) {
            try {
                projection?.stop()
            } catch (_: Exception) {
            }
        }
        thread?.quitSafely()
        thread = null
        handler = null
    }

    val isRunning: Boolean get() = running

    private fun imageToBitmap(image: Image): Bitmap? {
        val plane = image.planes[0]
        val buffer: ByteBuffer = plane.buffer
        val pixelStride = plane.pixelStride
        val rowStride = plane.rowStride
        val rowPadding = rowStride - pixelStride * width
        return try {
            val bitmap = Bitmap.createBitmap(
                width + rowPadding / pixelStride,
                height,
                Bitmap.Config.ARGB_8888
            )
            bitmap.copyPixelsFromBuffer(buffer)
            if (bitmap.width != width) {
                val cropped = Bitmap.createBitmap(bitmap, 0, 0, width, height)
                bitmap.recycle()
                cropped
            } else {
                bitmap
            }
        } catch (_: Exception) {
            null
        }
    }
}
