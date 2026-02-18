package com.example.antiscam

import android.accessibilityservice.AccessibilityService
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Button
import android.widget.TextView
import java.util.Locale
import java.util.concurrent.Executors

class ScamDetectorService : AccessibilityService(), TextToSpeech.OnInitListener {

    companion object {
        private const val TAG = "ScamDetector"
        private const val MONITOR_ALL_APPS = true
        private const val HOLD_MS = 2000L
        private const val COOLDOWN_MS = 10_000L
        private const val SCAM_THRESHOLD = 60.0
        private const val SAFE_THRESHOLD = 15.0
        private const val SAFE_COOLDOWN_MS = 30_000L
    }

    private var wm: WindowManager? = null
    private var redView: View? = null

    @Volatile private var redShown = false
    @Volatile private var alertFired = false

    // ─── Safe Overlay State ──────────────────────────────────────────────────
    private var safeView: View? = null
    @Volatile private var safeShown = false
    private var safeDismissedAt = 0L

    private var tts: TextToSpeech? = null
    private var ttsReady = false

    private val handler = Handler(Looper.getMainLooper())
    private var dismissRunnable: Runnable? = null
    private var tickRunnable: Runnable? = null
    private var dismissedAt = 0L

    // Audio
    private var audioManager: AudioManager? = null
    private var savedVolume = -1

    // Database
    private val dbExecutor = Executors.newSingleThreadExecutor()
    private lateinit var dao: ScamEventDao

    // Current detection context (for saving to DB)
    private var currentPkg = ""
    private var currentRisk = 0.0
    private var currentSnippet = ""

    private val MONITORED_PACKAGES = setOf(
        "com.phonepe.app", "com.phonepe.app.preprod",
        "com.google.android.apps.nbu.paisa.user",
        "net.one97.paytm", "in.org.npci.upiapp",
        "com.amazon.mShop.android.shopping",
        "com.mobikwik_new", "com.freecharge.android", "com.whatsapp",
    )

    // ─── Lifecycle ────────────────────────────────────────────────────────────

    override fun onServiceConnected() {
        super.onServiceConnected()
        ScamJudge.init(this)
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        tts = TextToSpeech(this, this)
        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        dao = ScamDatabase.getInstance(this).scamEventDao()
        startForegroundNotif()
        Log.i(TAG, "Service connected — ML threshold: ${SCAM_THRESHOLD}%")
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            tts?.setLanguage(Locale.ENGLISH)
            ttsReady = true

            // Listen for TTS completion to restore volume
            tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {}
                override fun onDone(utteranceId: String?) {
                    if (utteranceId == "SCAM_REPEAT") restoreVolume()
                }
                @Deprecated("Deprecated in Java")
                override fun onError(utteranceId: String?) {
                    restoreVolume()
                }
            })
        }
    }

    override fun onInterrupt() { removeRed() }
    override fun onDestroy() { super.onDestroy(); removeRed(); removeSafe(); tts?.shutdown() }

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
            .setContentText("TFLite ML model monitoring for UPI scams")
            .setSmallIcon(android.R.drawable.ic_lock_lock)
            .setContentIntent(pi).setOngoing(true).build()
        startForeground(1, notif)
    }

    // ─── Event Handling ───────────────────────────────────────────────────────

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        val pkg = event.packageName?.toString() ?: return
        if (pkg == "com.example.antiscam") return
        // Ignore system UI (notification shade, lock screen, volume panel)
        if (pkg == "com.android.systemui" || pkg == "com.android.keyguard") return
        if (!MONITOR_ALL_APPS && pkg !in MONITORED_PACKAGES) return
        if (event.eventType != AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED &&
            event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return

        if (dismissedAt > 0 && SystemClock.elapsedRealtime() - dismissedAt < COOLDOWN_MS) return
        if (redShown) return

        val root = rootInActiveWindow ?: return
        val text = extractText(root)
        if (text.isBlank()) return

        val risk = ScamJudge.calculateRisk(text)
        Log.d(TAG, "[$pkg] Risk: ${"%.1f".format(risk)}%  |  ${text.take(120)}")

        if (risk >= SCAM_THRESHOLD) {
            Log.w(TAG, "🚨 SCAM DETECTED (${"%.1f".format(risk)}%) in $pkg")
            currentPkg = pkg
            currentRisk = risk
            currentSnippet = text.take(200)
            showRed(risk)
        } else if (risk < SAFE_THRESHOLD && pkg in MONITORED_PACKAGES) {
            // ─── Safe Mode: show green banner for legitimate transactions ───
            val safeCooldownElapsed = safeDismissedAt == 0L ||
                SystemClock.elapsedRealtime() - safeDismissedAt > SAFE_COOLDOWN_MS
            if (!safeShown && safeCooldownElapsed) {
                currentPkg = pkg
                currentRisk = risk
                currentSnippet = text.take(200)
                showSafe()
            }
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

    private fun getAppLabel(pkg: String): String {
        return try {
            val pm = packageManager
            val ai = pm.getApplicationInfo(pkg, 0)
            pm.getApplicationLabel(ai).toString()
        } catch (e: Exception) { pkg }
    }

    // ─── Red Overlay ──────────────────────────────────────────────────────────

    private fun showRed(riskPercent: Double) {
        if (redShown || alertFired) return
        alertFired = true
        redShown = true

        // Dismiss safe overlay if it's showing
        if (safeShown) removeSafe()

        handler.post {
            try {
                val view = LayoutInflater.from(this).inflate(R.layout.overlay_scam, null)
                val tvCountdown = view.findViewById<TextView>(R.id.tv_countdown)
                val tvRisk = view.findViewById<TextView>(R.id.tv_risk)
                val btnBlock = view.findViewById<Button>(R.id.btn_block)

                tvRisk?.text = "Risk Score: ${"%.0f".format(riskPercent)}%"

                wm?.addView(view, overlayParams())
                redView = view

                setupDismiss(view, tvCountdown)
                setupBlock(btnBlock)
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
            restoreVolume()
            Log.i(TAG, "Red overlay REMOVED — 20s cooldown started")
        } catch (e: Exception) {
            Log.e(TAG, "removeRedNow error", e)
        }
    }

    private fun removeRed() {
        if (!redShown) return
        handler.post { removeRedNow() }
    }

    // ─── Safe Overlay (FR-07/08) ──────────────────────────────────────────────

    private fun showSafe() {
        if (safeShown) return
        safeShown = true

        handler.post {
            try {
                val view = LayoutInflater.from(this).inflate(R.layout.overlay_safe, null)
                wm?.addView(view, overlayParamsSafe())
                safeView = view

                // Auto-dismiss after 3 seconds
                handler.postDelayed({
                    removeSafe()
                }, 3000L)

                // Log to database
                saveEvent("SAFE")
                Log.i(TAG, "✅ Safe overlay shown for $currentPkg (risk ${"%.1f".format(currentRisk)}%)")
            } catch (e: Exception) {
                Log.e(TAG, "showSafe error", e)
                safeShown = false
            }
        }
    }

    private fun removeSafe() {
        if (!safeShown && safeView == null) return
        handler.post {
            try {
                if (safeView != null) {
                    wm?.removeView(safeView)
                    safeView = null
                }
                safeShown = false
                safeDismissedAt = SystemClock.elapsedRealtime()
                Log.i(TAG, "Safe overlay removed — ${SAFE_COOLDOWN_MS / 1000}s cooldown started")
            } catch (e: Exception) {
                Log.e(TAG, "removeSafe error", e)
            }
        }
    }

    /** Window params for safe overlay — slim top bar, not full screen */
    private fun overlayParamsSafe(): WindowManager.LayoutParams {
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_SYSTEM_ALERT

        val flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN

        return WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            type, flags, PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP
        }
    }

    // ─── Save to Database ─────────────────────────────────────────────────────

    private fun saveEvent(action: String) {
        val event = ScamEvent(
            packageName = currentPkg,
            appName = getAppLabel(currentPkg),
            riskScore = currentRisk,
            textSnippet = currentSnippet,
            action = action
        )
        dbExecutor.execute {
            try {
                dao.insert(event)
                Log.i(TAG, "Saved to DB: $action — $currentPkg (${currentRisk}%)")
            } catch (e: Exception) {
                Log.e(TAG, "DB insert error", e)
            }
        }
    }

    // ─── Block App ────────────────────────────────────────────────────────────

    private fun setupBlock(btnBlock: Button?) {
        btnBlock?.setOnClickListener {
            Log.i(TAG, "BLOCK pressed for $currentPkg")
            saveEvent("BLOCKED")

            // Force close the scam app
            try {
                performGlobalAction(GLOBAL_ACTION_HOME)
            } catch (e: Exception) {
                Log.e(TAG, "Block action error", e)
            }

            removeRedNow()
        }
    }

    // ─── Hold-to-Dismiss ──────────────────────────────────────────────────────

    private fun setupDismiss(view: View, tvCountdown: TextView?) {
        view.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
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
                        saveEvent("DISMISSED")
                        removeRedNow()
                    }
                    handler.postDelayed(dismissRunnable!!, HOLD_MS)
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
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

    // ─── Alerts (Demo Polish) ─────────────────────────────────────────────────

    private fun scamAlert() {
        vibrate(longArrayOf(0, 500, 200, 500, 200, 500))

        if (ttsReady) {
            boostVolume()

            val warningMsg = "WARNING! SCAM DETECTED! Do NOT enter your PIN. " +
                "This is a FRAUDULENT collect request. Close this app immediately!"

            // First utterance
            tts?.speak(warningMsg, TextToSpeech.QUEUE_FLUSH, null, "SCAM_FIRST")

            // Repeat after a brief pause for maximum audio impact
            tts?.playSilentUtterance(800L, TextToSpeech.QUEUE_ADD, "SCAM_PAUSE")
            tts?.speak(warningMsg, TextToSpeech.QUEUE_ADD, null, "SCAM_REPEAT")
        }
    }

    /** Boost media volume to max before TTS warning */
    private fun boostVolume() {
        try {
            audioManager?.let { am ->
                savedVolume = am.getStreamVolume(AudioManager.STREAM_MUSIC)
                val maxVol = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                am.setStreamVolume(AudioManager.STREAM_MUSIC, maxVol, 0)
                Log.d(TAG, "Volume boosted: $savedVolume → $maxVol")
            }
        } catch (e: Exception) {
            Log.e(TAG, "boostVolume error", e)
        }
    }

    /** Restore media volume to previous level */
    private fun restoreVolume() {
        try {
            if (savedVolume >= 0) {
                audioManager?.setStreamVolume(AudioManager.STREAM_MUSIC, savedVolume, 0)
                Log.d(TAG, "Volume restored to $savedVolume")
                savedVolume = -1
            }
        } catch (e: Exception) {
            Log.e(TAG, "restoreVolume error", e)
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
