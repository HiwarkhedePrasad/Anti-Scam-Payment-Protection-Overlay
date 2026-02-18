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
        btnOverlay.text = if (ov) "✅ Overlay Granted" else getString(R.string.enable_overlay_button)
        btnOverlay.isEnabled = !ov

        val sv = isServiceEnabled()
        btnAccessibility.text = if (sv) "✅ Service Active" else getString(R.string.enable_service_button)
        btnAccessibility.isEnabled = !sv

        val bt = isBatteryOptIgnored()
        btnBattery.text = if (bt) "✅ Battery Opt Disabled" else "Disable Battery Optimization"
        btnBattery.isEnabled = !bt

        tvStatus.text = if (sv) "● ML Model Active" else "○ Service Inactive"
        tvStatus.setTextColor(if (sv) 0xFF3FB950.toInt() else 0xFFF85149.toInt())
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
                tvNoHistory.text = if (events.isEmpty()) "No threats yet" else "${events.size} events"

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
