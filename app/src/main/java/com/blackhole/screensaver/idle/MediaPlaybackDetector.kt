package com.blackhole.screensaver.idle

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.os.Build

/**
 * Detects active media/game audio so the screensaver stays off during playback
 * (YouTube, Netflix, music apps, etc.).
 */
object MediaPlaybackDetector {

    fun isMediaPlaying(context: Context): Boolean {
        val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            return am.activePlaybackConfigurations.any { config ->
                val usage = config.audioAttributes.usage
                usage == AudioAttributes.USAGE_MEDIA ||
                    usage == AudioAttributes.USAGE_GAME ||
                    usage == AudioAttributes.USAGE_UNKNOWN
            }
        }
        @Suppress("DEPRECATION")
        return am.isMusicActive
    }
}
