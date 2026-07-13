package com.blackhole.screensaver.ui

import android.content.Intent
import android.content.pm.PackageManager
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!AppPrefs.privacyAccepted) {
            startActivity(Intent(this, PrivacyPolicyActivity::class.java))
            finish()
            return
        }

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        applyEdgeToEdgeInsets(binding.root)

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
            startActivity(Intent(this, AccessibilityGuideActivity::class.java))
        }
        binding.accessibilityGuideButton.setOnClickListener {
            startActivity(Intent(this, AccessibilityGuideActivity::class.java))
        }
        binding.privacyButton.setOnClickListener {
            startActivity(Intent(this, PrivacyPolicyActivity::class.java))
        }

        binding.captureButton.visibility = View.GONE
        binding.captureStatus.visibility = View.GONE

        handleEnableExtra(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleEnableExtra(intent)
    }

    override fun onResume() {
        super.onResume()
        if (!::binding.isInitialized) return
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
        FeatureController.setEnabled(this, true)
        syncToggleUi()
        Toast.makeText(this, R.string.toast_enabled, Toast.LENGTH_SHORT).show()
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

        binding.overlayStatus.text = status(overlay)
        binding.notificationStatus.text = status(notif)
        binding.accessibilityStatus.text = status(a11y)

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
