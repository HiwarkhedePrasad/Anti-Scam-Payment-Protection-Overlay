package com.example.antiscam

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.accessibility.AccessibilityManager
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
            if (!checkOverlayPermission()) {
                requestOverlayPermission()
            } else {
                Toast.makeText(this, "Overlay Permission already granted", Toast.LENGTH_SHORT).show()
            }
        }

        btnEnableAccessibility.setOnClickListener {
            if (!isAccessibilityServiceEnabled(ScamDetectorService::class.java)) {
                openAccessibilitySettings()
            } else {
                Toast.makeText(this, "Accessibility Service already enabled", Toast.LENGTH_SHORT).show()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        updateButtonStates()
    }

    private fun updateButtonStates() {
        btnEnableOverlay.isEnabled = !checkOverlayPermission()
        btnEnableOverlay.text = if (checkOverlayPermission()) "Overlay Permission Granted" else getString(R.string.enable_overlay_button)
        
        val serviceEnabled = isAccessibilityServiceEnabled(ScamDetectorService::class.java)
        btnEnableAccessibility.isEnabled = !serviceEnabled
        btnEnableAccessibility.text = if (serviceEnabled) "Service Enabled" else getString(R.string.enable_service_button)
    }

    private fun checkOverlayPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Settings.canDrawOverlays(this)
        } else {
            true
        }
    }

    private fun requestOverlayPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
            startActivity(intent)
        }
    }

    private fun isAccessibilityServiceEnabled(service: Class<*>?): Boolean {
        // Correct way to check if YOUR specific service is enabled
        val am = getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager
        val enabledServices = Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
        
        if (enabledServices.isNullOrEmpty()) return false
        
        val colonSplitter = android.text.TextUtils.SimpleStringSplitter(':')
        colonSplitter.setString(enabledServices)
        
        val myService = "${packageName}/${service?.canonicalName}"
        val myServiceShort = "${packageName}/.${service?.simpleName}"

        while (colonSplitter.hasNext()) {
            val componentName = colonSplitter.next()
            if (componentName.equals(myService, ignoreCase = true) || 
                componentName.equals(myServiceShort, ignoreCase = true)) {
                return true
            }
        }
        return false
    }
    
    // Easier check using AccessibilityManager is hard because getEnabledAccessibilityServiceList returns info, 
    // but matching it to our class is sometimes tricky with component names. 
    // The Settings.Secure check is robust.

    private fun openAccessibilitySettings() {
        val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
        startActivity(intent)
    }
}
