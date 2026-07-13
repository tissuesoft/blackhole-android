package com.blackhole.screensaver.prefs

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit

object AppPrefs {
    private const val PREFS = "blackhole_prefs"
    private const val KEY_ENABLED = "enabled"
    private const val KEY_IDLE_MINUTES = "idle_minutes"
    private const val KEY_CAPTURE_GRANTED = "capture_granted"
    private const val KEY_PRIVACY_ACCEPTED = "privacy_accepted"
    private const val DEFAULT_IDLE_MINUTES = 3

    private lateinit var prefs: SharedPreferences

    fun init(context: Context) {
        prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    }

    var enabled: Boolean
        get() = prefs.getBoolean(KEY_ENABLED, false)
        set(value) = prefs.edit { putBoolean(KEY_ENABLED, value) }

    var idleMinutes: Int
        get() = prefs.getInt(KEY_IDLE_MINUTES, DEFAULT_IDLE_MINUTES).coerceIn(1, 60)
        set(value) = prefs.edit { putInt(KEY_IDLE_MINUTES, value.coerceIn(1, 60)) }

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

    const val KEY_ENABLED_CONST = KEY_ENABLED
    const val KEY_IDLE_MINUTES_CONST = KEY_IDLE_MINUTES
}
