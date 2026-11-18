package com.senxia.adskip

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {

    private lateinit var statusText: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(48, 64, 48, 48)
        }

        val title = TextView(this).apply {
            text = getString(R.string.welcome_title)
            textSize = 24f
            setTextColor(0xFF4E2D75.toInt())
            gravity = android.view.Gravity.CENTER
        }

        statusText = TextView(this).apply {
            textSize = 16f
            gravity = android.view.Gravity.CENTER
            setPadding(0, 24, 0, 24)
        }

        val enableBtn = Button(this).apply {
            text = getString(R.string.enable_accessibility)
            setOnClickListener { openAccessibilitySettings() }
        }

        val settingsBtn = Button(this).apply {
            text = getString(R.string.open_settings)
            setOnClickListener {
                startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                    data = Uri.parse("package:$packageName")
                })
            }
        }

        val tipsTitle = TextView(this).apply {
            text = getString(R.string.tips_title)
            textSize = 15f
            setPadding(0, 32, 0, 8)
        }

        val tips = TextView(this).apply {
            text = getString(R.string.tips)
            textSize = 13f
        }

        root.addView(title)
        root.addView(statusText)
        root.addView(enableBtn)
        root.addView(settingsBtn)
        root.addView(tipsTitle)
        root.addView(tips)

        setContentView(root)
    }

    override fun onResume() {
        super.onResume()
        updateStatus()
    }

    private fun updateStatus() {
        val on = isAccessibilityEnabled(this)
        statusText.text = getString(R.string.accessibility_status,
            getString(if (on) R.string.status_on else R.string.status_off))
        if (!on) {
            Toast.makeText(this, "请先开启无障碍服务，否则无法跳广告", Toast.LENGTH_LONG).show()
        }
    }

    private fun openAccessibilitySettings() {
        startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
    }

    private fun isAccessibilityEnabled(context: Context): Boolean {
        val expected = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        val target = "${context.packageName}/${AdSkipService::class.java.name}"
        return expected.split(":").any {
            it.equals(target, ignoreCase = true)
        }
    }
}
