package com.blackhole.screensaver.ui

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.blackhole.screensaver.databinding.ActivityAccessibilityGuideBinding
import com.blackhole.screensaver.prefs.PermissionHelper

class AccessibilityGuideActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val binding = ActivityAccessibilityGuideBinding.inflate(layoutInflater)
        setContentView(binding.root)
        applyEdgeToEdgeInsets(binding.root)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        title = getString(com.blackhole.screensaver.R.string.a11y_guide_title)

        binding.openAccessibilityButton.setOnClickListener {
            startActivity(PermissionHelper.accessibilitySettingsIntent())
        }
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }
}
