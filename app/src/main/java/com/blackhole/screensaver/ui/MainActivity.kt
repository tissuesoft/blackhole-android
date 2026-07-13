package com.blackhole.screensaver.ui

import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.SeekBar
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.blackhole.screensaver.BlackholeApp
import com.blackhole.screensaver.R
import com.blackhole.screensaver.databinding.ActivityMainBinding
import com.blackhole.screensaver.prefs.AppPrefs
import com.blackhole.screensaver.prefs.PermissionHelper
import com.blackhole.screensaver.service.FeatureController

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private var updatingUi = false

    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) {
            refreshPermissionUi()
        }

    private val captureLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == Activity.RESULT_OK && result.data != null) {
                AppPrefs.captureGranted = true
                refreshPermissionUi()
                if (AppPrefs.enabled || pendingEnableAfterCapture) {
                    pendingEnableAfterCapture = false
                    AppPrefs.enabled = true
                    FeatureController.setEnabled(
                        this,
                        true,
                        result.resultCode,
                        result.data
                    )
                    syncToggleUi()
                    Toast.makeText(this, R.string.toast_enabled, Toast.LENGTH_SHORT).show()
                }
            } else {
                pendingEnableAfterCapture = false
                AppPrefs.captureGranted = false
                if (AppPrefs.enabled) {
                    AppPrefs.enabled = false
                    FeatureController.setEnabled(this, false)
                    syncToggleUi()
                }
                refreshPermissionUi()
            }
        }

    private var pendingEnableAfterCapture = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.idleSeekBar.max = 59 // 1..60
        binding.idleSeekBar.progress = (AppPrefs.idleMinutes - 1).coerceIn(0, 59)
        updateIdleLabel(AppPrefs.idleMinutes)

        binding.idleSeekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                val minutes = progress + 1
                updateIdleLabel(minutes)
                if (fromUser) {
                    AppPrefs.idleMinutes = minutes
                    sendBroadcast(
                        Intent(BlackholeApp.ACTION_IDLE_MINUTES_CHANGED).setPackage(packageName)
                    )
                }
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
            override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
        })

        binding.enableSwitch.setOnCheckedChangeListener { _, isChecked ->
            if (updatingUi) return@setOnCheckedChangeListener
            if (isChecked) {
                tryEnable()
            } else {
                FeatureController.setEnabled(this, false)
                syncToggleUi()
                Toast.makeText(this, R.string.toast_disabled, Toast.LENGTH_SHORT).show()
            }
        }

        binding.overlayButton.setOnClickListener {
            startActivity(PermissionHelper.overlaySettingsIntent(this))
        }
        binding.notificationButton.setOnClickListener { requestNotifications() }
        binding.accessibilityButton.setOnClickListener {
            startActivity(PermissionHelper.accessibilitySettingsIntent())
        }
        binding.captureButton.setOnClickListener { requestCapture(enableAfter = false) }

        handleEnableExtra(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleEnableExtra(intent)
    }

    override fun onResume() {
        super.onResume()
        refreshPermissionUi()
        syncToggleUi()
    }

    private fun handleEnableExtra(intent: Intent?) {
        if (intent?.getBooleanExtra(EXTRA_REQUEST_ENABLE, false) == true) {
            tryEnable()
        }
    }

    private fun tryEnable() {
        if (!PermissionHelper.canDrawOverlays(this) ||
            !PermissionHelper.notificationsAllowed(this) ||
            !PermissionHelper.isAccessibilityEnabled(this)
        ) {
            updatingUi = true
            binding.enableSwitch.isChecked = false
            updatingUi = false
            syncToggleUi()
            Toast.makeText(this, R.string.toast_need_permissions, Toast.LENGTH_LONG).show()
            refreshPermissionUi()
            return
        }
        // Always re-request MediaProjection when enabling: Android 14+ tokens are single-use
        // and do not survive process death reliably.
        pendingEnableAfterCapture = true
        requestCapture(enableAfter = true)
    }

    private fun requestCapture(enableAfter: Boolean) {
        pendingEnableAfterCapture = enableAfter
        val mpm = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        captureLauncher.launch(mpm.createScreenCaptureIntent())
    }

    private fun requestNotifications() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(
                    this,
                    android.Manifest.permission.POST_NOTIFICATIONS
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                notificationPermissionLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
                return
            }
        }
        startActivity(PermissionHelper.appNotificationSettingsIntent(this))
    }

    private fun syncToggleUi() {
        updatingUi = true
        binding.enableSwitch.isChecked = AppPrefs.enabled
        binding.toggleStateLabel.setText(if (AppPrefs.enabled) R.string.toggle_on else R.string.toggle_off)
        binding.toggleStateLabel.setTextColor(
            ContextCompat.getColor(this, if (AppPrefs.enabled) R.color.on else R.color.off)
        )
        updatingUi = false
    }

    private fun updateIdleLabel(minutes: Int) {
        binding.idleValueText.text = getString(R.string.idle_value_format, minutes)
    }

    private fun refreshPermissionUi() {
        fun status(ok: Boolean) =
            getString(if (ok) R.string.permission_granted else R.string.permission_missing)

        val overlay = PermissionHelper.canDrawOverlays(this)
        val notif = PermissionHelper.notificationsAllowed(this)
        val a11y = PermissionHelper.isAccessibilityEnabled(this)
        val capture = AppPrefs.captureGranted

        binding.overlayStatus.text = status(overlay)
        binding.notificationStatus.text = status(notif)
        binding.accessibilityStatus.text = status(a11y)
        binding.captureStatus.text = status(capture)

        val ready = overlay && notif && a11y
        binding.statusText.setText(if (ready) R.string.status_ready else R.string.status_need_perms)

        binding.overlayButton.visibility = if (overlay) View.GONE else View.VISIBLE
        binding.notificationButton.visibility = if (notif) View.GONE else View.VISIBLE
        binding.accessibilityButton.visibility = if (a11y) View.GONE else View.VISIBLE
    }

    companion object {
        const val EXTRA_REQUEST_ENABLE = "request_enable"
    }
}
