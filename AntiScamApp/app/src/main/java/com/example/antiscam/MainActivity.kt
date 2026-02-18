package com.example.antiscam

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {

    private lateinit var btnEnableOverlay: Button
    private lateinit var btnEnableAccessibility: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        btnEnableOverlay = findViewById(R.id.btn_enable_overlay)
        btnEnableAccessibility = findViewById(R.id.btn_enable_accessibility)

        btnEnableOverlay.setOnClickListener {
            if (!hasOverlayPermission()) {
                requestOverlayPermission()
            } else {
                Toast.makeText(this, "Overlay permission already granted ✅", Toast.LENGTH_SHORT).show()
            }
        }

        btnEnableAccessibility.setOnClickListener {
            if (!isScamServiceEnabled()) {
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            } else {
                Toast.makeText(this, "Accessibility service already active ✅", Toast.LENGTH_SHORT).show()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        updateButtonStates()
    }

    private fun updateButtonStates() {
        val overlayOk = hasOverlayPermission()
        btnEnableOverlay.text = if (overlayOk) getString(R.string.overlay_granted) else getString(R.string.enable_overlay_button)
        btnEnableOverlay.isEnabled = !overlayOk

        val serviceOk = isScamServiceEnabled()
        btnEnableAccessibility.text = if (serviceOk) getString(R.string.service_enabled) else getString(R.string.enable_service_button)
        btnEnableAccessibility.isEnabled = !serviceOk
    }

    private fun hasOverlayPermission(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(this)

    private fun requestOverlayPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
        }
    }

    private fun isScamServiceEnabled(): Boolean {
        val enabledServices = Settings.Secure.getString(
            contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false

        val target = "$packageName/${ScamDetectorService::class.java.canonicalName}"
        val targetShort = "$packageName/.${ScamDetectorService::class.java.simpleName}"

        return enabledServices.split(":").any { component ->
            component.equals(target, ignoreCase = true) ||
                    component.equals(targetShort, ignoreCase = true)
        }
    }
}
