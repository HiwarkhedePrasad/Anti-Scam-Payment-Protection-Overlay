package com.example.antiscam

import android.accessibilityservice.AccessibilityService
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.speech.tts.TextToSpeech
import android.util.Log
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.TextView
import java.util.Locale

class ScamDetectorService : AccessibilityService(), TextToSpeech.OnInitListener {

    companion object {
        private const val TAG = "ScamDetector"
        private const val MONITOR_ALL_APPS = true
        private const val HOLD_MS = 2000L
        private const val COOLDOWN_MS = 20_000L
        private const val SCAM_THRESHOLD = 85.0   // 85% confidence → trigger alert

        private val MONITORED_PACKAGES = setOf(
            "com.phonepe.app", "com.phonepe.app.preprod",
            "com.google.android.apps.nbu.paisa.user",
            "net.one97.paytm", "in.org.npci.upiapp",
            "com.amazon.mShop.android.shopping",
            "com.mobikwik_new", "com.freecharge.android", "com.whatsapp",
        )
    }

    private var wm: WindowManager? = null
    private var redView: View? = null

    @Volatile private var redShown = false
    @Volatile private var alertFired = false

    private var tts: TextToSpeech? = null
    private var ttsReady = false

    private val handler = Handler(Looper.getMainLooper())
    private var dismissRunnable: Runnable? = null
    private var tickRunnable: Runnable? = null
    private var dismissedAt = 0L

    // ─── Lifecycle ────────────────────────────────────────────────────────────

    override fun onServiceConnected() {
        super.onServiceConnected()
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        tts = TextToSpeech(this, this)
        startForegroundNotif()
        Log.i(TAG, "Service connected — ML threshold: ${SCAM_THRESHOLD}%")
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            tts?.setLanguage(Locale.ENGLISH)
            ttsReady = true
        }
    }

    override fun onInterrupt() { removeRed() }
    override fun onDestroy() { super.onDestroy(); removeRed(); tts?.shutdown() }

    private fun startForegroundNotif() {
        val channelId = "antiscam_guard"
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(
                NotificationChannel(channelId, "AntiScam Guard", NotificationManager.IMPORTANCE_LOW).apply {
                    description = "ML-powered UPI scam monitoring"
                }
            )
        }
        val pi = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val notif = Notification.Builder(this, channelId)
            .setContentTitle("AntiScam Guard Active")
            .setContentText("ML model monitoring for UPI scams")
            .setSmallIcon(android.R.drawable.ic_lock_lock)
            .setContentIntent(pi).setOngoing(true).build()
        startForeground(1, notif)
    }

    // ─── Event Handling ───────────────────────────────────────────────────────

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        val pkg = event.packageName?.toString() ?: return
        if (pkg == "com.example.antiscam") return
        if (!MONITOR_ALL_APPS && pkg !in MONITORED_PACKAGES) return
        if (event.eventType != AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED &&
            event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return

        // Cooldown after dismiss
        if (dismissedAt > 0 && SystemClock.elapsedRealtime() - dismissedAt < COOLDOWN_MS) return

        // Don't process while overlay is up
        if (redShown) return

        val root = rootInActiveWindow ?: return
        val text = extractText(root)
        if (text.isBlank()) return

        // ──── ML INFERENCE ────
        val risk = ScamJudge.calculateRisk(text)
        Log.d(TAG, "[$pkg] Risk: ${"%.1f".format(risk)}%  |  ${text.take(120)}")

        if (risk >= SCAM_THRESHOLD) {
            Log.w(TAG, "🚨 SCAM DETECTED (${"%.1f".format(risk)}%) in $pkg")
            showRed(risk)
        }
    }

    private fun extractText(node: AccessibilityNodeInfo?): String {
        if (node == null) return ""
        val sb = StringBuilder(512)
        fun walk(n: AccessibilityNodeInfo?) {
            if (n == null) return
            n.text?.let { sb.append(it).append(' ') }
            n.contentDescription?.let { sb.append(it).append(' ') }
            for (i in 0 until n.childCount) walk(n.getChild(i))
        }
        walk(node)
        return sb.toString()
    }

    // ─── Red Overlay ──────────────────────────────────────────────────────────

    private fun showRed(riskPercent: Double) {
        if (redShown || alertFired) return
        alertFired = true
        redShown = true

        handler.post {
            try {
                val view = LayoutInflater.from(this).inflate(R.layout.overlay_scam, null)
                val tvCountdown = view.findViewById<TextView>(R.id.tv_countdown)
                val tvRisk = view.findViewById<TextView>(R.id.tv_risk)

                // Show ML confidence score
                tvRisk?.text = "Risk Score: ${"%.0f".format(riskPercent)}%"

                wm?.addView(view, overlayParams())
                redView = view

                setupDismiss(view, tvCountdown)
                scamAlert()
                Log.i(TAG, "Red overlay shown — risk ${"%.1f".format(riskPercent)}%")
            } catch (e: Exception) {
                Log.e(TAG, "showRed error", e)
                redShown = false
                alertFired = false
            }
        }
    }

    private fun removeRedNow() {
        try {
            dismissRunnable?.let { handler.removeCallbacks(it) }
            tickRunnable?.let { handler.removeCallbacks(it) }
            dismissRunnable = null
            tickRunnable = null
            if (redView != null) {
                wm?.removeView(redView)
                redView = null
            }
            redShown = false
            alertFired = false
            dismissedAt = SystemClock.elapsedRealtime()
            tts?.stop()
            Log.i(TAG, "Red overlay REMOVED — 20s cooldown started")
        } catch (e: Exception) {
            Log.e(TAG, "removeRedNow error", e)
        }
    }

    private fun removeRed() {
        if (!redShown) return
        handler.post { removeRedNow() }
    }

    // ─── Hold-to-Dismiss ──────────────────────────────────────────────────────

    private fun setupDismiss(view: View, tvCountdown: TextView?) {
        view.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    Log.d(TAG, "TOUCH DOWN — hold timer started")
                    var sec = (HOLD_MS / 1000).toInt()
                    tvCountdown?.text = "Hold $sec..."

                    tickRunnable = object : Runnable {
                        override fun run() {
                            sec--
                            if (sec > 0) {
                                tvCountdown?.text = "Hold $sec..."
                                handler.postDelayed(this, 1000L)
                            } else {
                                tvCountdown?.text = "Releasing..."
                            }
                        }
                    }
                    handler.postDelayed(tickRunnable!!, 1000L)

                    dismissRunnable = Runnable {
                        Log.i(TAG, "HOLD COMPLETE — removing overlay")
                        removeRedNow()
                    }
                    handler.postDelayed(dismissRunnable!!, HOLD_MS)
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    Log.d(TAG, "TOUCH UP — timer cancelled")
                    tickRunnable?.let { handler.removeCallbacks(it) }
                    dismissRunnable?.let { handler.removeCallbacks(it) }
                    dismissRunnable = null
                    tickRunnable = null
                    tvCountdown?.text = "Touch & hold to dismiss"
                    true
                }
                else -> true
            }
        }
    }

    // ─── Window Params ────────────────────────────────────────────────────────

    private fun overlayParams(): WindowManager.LayoutParams {
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_SYSTEM_ALERT

        val flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON

        return WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            type, flags, PixelFormat.TRANSLUCENT
        )
    }

    // ─── Alerts ───────────────────────────────────────────────────────────────

    private fun scamAlert() {
        vibrate(longArrayOf(0, 500, 200, 500))
        if (ttsReady) {
            tts?.speak(
                "Warning! Scam detected. Do not enter your UPI PIN. This is a fraud request.",
                TextToSpeech.QUEUE_FLUSH, null, "SCAM"
            )
        }
    }

    private fun vibrate(pattern: LongArray) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                (getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager)
                    .defaultVibrator.vibrate(VibrationEffect.createWaveform(pattern, -1))
            } else {
                @Suppress("DEPRECATION")
                val v = getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                    v.vibrate(VibrationEffect.createWaveform(pattern, -1))
                else @Suppress("DEPRECATION") v.vibrate(pattern, -1)
            }
        } catch (e: Exception) { Log.e(TAG, "vibrate error", e) }
    }
}
