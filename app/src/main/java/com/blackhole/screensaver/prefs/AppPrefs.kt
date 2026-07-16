package com.blackhole.screensaver.prefs

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit

object AppPrefs {
    private const val PREFS = "blackhole_prefs"
    private const val KEY_ENABLED = "enabled"
    private const val KEY_IDLE_SECONDS = "idle_seconds"
    private const val KEY_IDLE_MINUTES_LEGACY = "idle_minutes"
    private const val KEY_BH_SIZE_INDEX = "bh_size_index"
    private const val KEY_CAPTURE_GRANTED = "capture_granted"
    private const val KEY_PRIVACY_ACCEPTED = "privacy_accepted"

    const val MIN_IDLE_SECONDS = 10
    const val MAX_IDLE_SECONDS = 600
    const val IDLE_STEP_SECONDS = 5
    private const val DEFAULT_IDLE_SECONDS = 60

    /** 0 = 1× (smallest), 1 = 1.5×, 2 = 2× */
    const val BH_SIZE_INDEX_MIN = 0
    const val BH_SIZE_INDEX_MAX = 2
    private const val DEFAULT_BH_SIZE_INDEX = 0

    private val BH_SIZE_SCALES = floatArrayOf(1f, 1.5f, 2f)

    private lateinit var prefs: SharedPreferences

    fun init(context: Context) {
        prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        migrateIdleMinutesIfNeeded()
    }

    var enabled: Boolean
        get() = prefs.getBoolean(KEY_ENABLED, false)
        set(value) = prefs.edit { putBoolean(KEY_ENABLED, value) }

    var idleSeconds: Int
        get() = snapIdleSeconds(prefs.getInt(KEY_IDLE_SECONDS, DEFAULT_IDLE_SECONDS))
        set(value) = prefs.edit { putInt(KEY_IDLE_SECONDS, snapIdleSeconds(value)) }

    var blackholeSizeIndex: Int
        get() = prefs.getInt(KEY_BH_SIZE_INDEX, DEFAULT_BH_SIZE_INDEX)
            .coerceIn(BH_SIZE_INDEX_MIN, BH_SIZE_INDEX_MAX)
        set(value) = prefs.edit {
            putInt(
                KEY_BH_SIZE_INDEX,
                value.coerceIn(BH_SIZE_INDEX_MIN, BH_SIZE_INDEX_MAX)
            )
        }

    val blackholeSizeScale: Float
        get() = BH_SIZE_SCALES[blackholeSizeIndex]

    var captureGranted: Boolean
        get() = prefs.getBoolean(KEY_CAPTURE_GRANTED, false)
        set(value) = prefs.edit { putBoolean(KEY_CAPTURE_GRANTED, value) }

    var privacyAccepted: Boolean
        get() = prefs.getBoolean(KEY_PRIVACY_ACCEPTED, false)
        set(value) = prefs.edit { putBoolean(KEY_PRIVACY_ACCEPTED, value) }

    fun registerListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) {
        prefs.registerOnSharedPreferenceChangeListener(listener)
    }

    fun unregisterListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) {
        prefs.unregisterOnSharedPreferenceChangeListener(listener)
    }

    fun snapIdleSeconds(seconds: Int): Int {
        val clamped = seconds.coerceIn(MIN_IDLE_SECONDS, MAX_IDLE_SECONDS)
        val steps = ((clamped - MIN_IDLE_SECONDS + IDLE_STEP_SECONDS / 2) / IDLE_STEP_SECONDS)
        return (MIN_IDLE_SECONDS + steps * IDLE_STEP_SECONDS).coerceIn(MIN_IDLE_SECONDS, MAX_IDLE_SECONDS)
    }

    fun idleSecondsToProgress(seconds: Int): Int =
        (snapIdleSeconds(seconds) - MIN_IDLE_SECONDS) / IDLE_STEP_SECONDS

    fun progressToIdleSeconds(progress: Int): Int =
        MIN_IDLE_SECONDS + progress.coerceAtLeast(0) * IDLE_STEP_SECONDS

    val idleSeekBarMax: Int
        get() = (MAX_IDLE_SECONDS - MIN_IDLE_SECONDS) / IDLE_STEP_SECONDS

    private fun migrateIdleMinutesIfNeeded() {
        if (prefs.contains(KEY_IDLE_SECONDS)) return
        if (!prefs.contains(KEY_IDLE_MINUTES_LEGACY)) return
        val minutes = prefs.getInt(KEY_IDLE_MINUTES_LEGACY, 3)
        prefs.edit {
            putInt(KEY_IDLE_SECONDS, snapIdleSeconds(minutes * 60))
            remove(KEY_IDLE_MINUTES_LEGACY)
        }
    }

    const val KEY_ENABLED_CONST = KEY_ENABLED
    const val KEY_IDLE_SECONDS_CONST = KEY_IDLE_SECONDS
    const val KEY_BH_SIZE_INDEX_CONST = KEY_BH_SIZE_INDEX
}
