package com.example.antiscam

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {

    private lateinit var btnOverlay: Button
    private lateinit var btnAccessibility: Button
    private lateinit var btnBattery: Button
    private lateinit var tvStatBlocked: TextView
    private lateinit var tvStatTotal: TextView
    private lateinit var tvStatRisk: TextView
    private lateinit var tvNoHistory: TextView
    private lateinit var tvStatus: TextView
    private lateinit var rvHistory: RecyclerView

    private val dbExecutor = Executors.newSingleThreadExecutor()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // Bind views
        btnOverlay = findViewById(R.id.btn_enable_overlay)
        btnAccessibility = findViewById(R.id.btn_enable_accessibility)
        btnBattery = findViewById(R.id.btn_battery_opt)
        tvStatBlocked = findViewById(R.id.tv_stat_blocked)
        tvStatTotal = findViewById(R.id.tv_stat_total)
        tvStatRisk = findViewById(R.id.tv_stat_risk)
        tvNoHistory = findViewById(R.id.tv_no_history)
        tvStatus = findViewById(R.id.tv_status)
        rvHistory = findViewById(R.id.rv_history)

        rvHistory.layoutManager = LinearLayoutManager(this)

        // Setup buttons
        btnOverlay.setOnClickListener {
            if (!hasOverlayPermission()) requestOverlayPermission()
            else Toast.makeText(this, "✅ Already granted", Toast.LENGTH_SHORT).show()
        }

        btnAccessibility.setOnClickListener {
            if (!isServiceEnabled()) startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            else Toast.makeText(this, "✅ Already active", Toast.LENGTH_SHORT).show()
        }

        btnBattery.setOnClickListener {
            if (!isBatteryOptIgnored()) {
                startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                    data = Uri.parse("package:$packageName")
                })
            } else Toast.makeText(this, "✅ Already disabled", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onResume() {
        super.onResume()
        updateButtons()
        loadStats()
    }

    private fun updateButtons() {
        val ov = hasOverlayPermission()
        if (ov) {
            btnOverlay.text = "✅  Overlay Granted"
            btnOverlay.isEnabled = false
            btnOverlay.setTextColor(getColor(R.color.text_muted))
            btnOverlay.alpha = 0.5f
        } else {
            btnOverlay.text = getString(R.string.enable_overlay_button)
            btnOverlay.isEnabled = true
            btnOverlay.setTextColor(getColor(R.color.accent_red))
            btnOverlay.alpha = 1.0f
        }

        val sv = isServiceEnabled()
        if (sv) {
            btnAccessibility.text = "✅  Service Active"
            btnAccessibility.isEnabled = false
            btnAccessibility.setTextColor(getColor(R.color.text_muted))
            btnAccessibility.alpha = 0.5f
        } else {
            btnAccessibility.text = getString(R.string.enable_service_button)
            btnAccessibility.isEnabled = true
            btnAccessibility.setTextColor(getColor(R.color.accent_blue))
            btnAccessibility.alpha = 1.0f
        }

        val bt = isBatteryOptIgnored()
        if (bt) {
            btnBattery.text = "✅  Battery Optimized"
            btnBattery.isEnabled = false
            btnBattery.setTextColor(getColor(R.color.text_muted))
            btnBattery.alpha = 0.5f
        } else {
            btnBattery.text = "Disable Battery Optimization"
            btnBattery.isEnabled = true
            btnBattery.setTextColor(getColor(R.color.accent_amber))
            btnBattery.alpha = 1.0f
        }

        // Status chip
        if (sv) {
            tvStatus.text = "●  ML Model Active"
            tvStatus.setTextColor(getColor(R.color.accent_green))
            tvStatus.setBackgroundResource(R.drawable.bg_status_active)
        } else {
            tvStatus.text = "○  Service Inactive"
            tvStatus.setTextColor(getColor(R.color.accent_red))
            tvStatus.setBackgroundResource(R.drawable.bg_status_inactive)
        }
    }

    private fun loadStats() {
        val dao = ScamDatabase.getInstance(this).scamEventDao()
        dbExecutor.execute {
            val total = dao.getTotalCount()
            val blocked = dao.getBlockedCount()
            val avgRisk = dao.getAverageRisk() ?: 0.0
            val events = dao.getAll()

            runOnUiThread {
                tvStatBlocked.text = "$blocked"
                tvStatTotal.text = "$total"
                tvStatRisk.text = "${"%.0f".format(avgRisk)}%"

                if (events.isEmpty()) {
                    tvNoHistory.text = "No events yet"
                } else {
                    tvNoHistory.text = "${events.size} events"
                }

                rvHistory.adapter = ScamEventAdapter(events)
            }
        }
    }

    private fun hasOverlayPermission() =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(this)

    private fun requestOverlayPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M)
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
    }

    private fun isBatteryOptIgnored(): Boolean =
        (getSystemService(Context.POWER_SERVICE) as PowerManager).isIgnoringBatteryOptimizations(packageName)

    private fun isServiceEnabled(): Boolean {
        val s = Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: return false
        val target = "$packageName/${ScamDetectorService::class.java.canonicalName}"
        val short = "$packageName/.${ScamDetectorService::class.java.simpleName}"
        return s.split(":").any { it.equals(target, true) || it.equals(short, true) }
    }
}
