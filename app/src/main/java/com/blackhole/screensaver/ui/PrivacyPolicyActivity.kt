package com.blackhole.screensaver.ui

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.blackhole.screensaver.R
import com.blackhole.screensaver.databinding.ActivityPrivacyPolicyBinding
import com.blackhole.screensaver.prefs.AppPrefs

class PrivacyPolicyActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val binding = ActivityPrivacyPolicyBinding.inflate(layoutInflater)
        setContentView(binding.root)
        applyEdgeToEdgeInsets(binding.root)

        val needsConsent = !AppPrefs.privacyAccepted
        if (needsConsent) {
            binding.privacyCheck.visibility = View.VISIBLE
            binding.privacyAgreeButton.visibility = View.VISIBLE
            binding.privacyAgreeButton.isEnabled = false
        } else {
            binding.privacyCheck.visibility = View.GONE
            binding.privacyAgreeButton.visibility = View.GONE
            supportActionBar?.setDisplayHomeAsUpEnabled(true)
        }

        binding.privacyCheck.setOnCheckedChangeListener { _, checked ->
            binding.privacyAgreeButton.isEnabled = checked
        }

        binding.privacyAgreeButton.setOnClickListener {
            if (!binding.privacyCheck.isChecked) {
                Toast.makeText(this, R.string.privacy_need_check, Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            AppPrefs.privacyAccepted = true
            startActivity(
                Intent(this, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NEW_TASK)
            )
            finish()
        }
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (!AppPrefs.privacyAccepted) {
            // First-run consent: back exits the app instead of skipping policy.
            finishAffinity()
        } else {
            super.onBackPressed()
        }
    }
}
