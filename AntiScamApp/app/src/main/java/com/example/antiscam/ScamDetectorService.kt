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

        private val MONITORED_PACKAGES = setOf(
            "com.phonepe.app", "com.phonepe.app.preprod",
            "com.google.android.apps.nbu.paisa.user",
            "net.one97.paytm", "in.org.npci.upiapp",
            "com.amazon.mShop.android.shopping",
            "com.mobikwik_new", "com.freecharge.android", "com.whatsapp",
        )

        private val SCAM_A = listOf("request from", "collect request", "payment request", "requesting money")
        private val SCAM_B = listOf("enter upi pin", "enter pin", "upi pin", "confirm with pin", "authenticate")
        private val SAFE_A = listOf("paying", "pay to", "sending")
        private val SAFE_B = listOf("₹", "rs.", "inr")
    }

    private var wm: WindowManager? = null
    private var redView: View? = null
    private var greenView: View? = null

    // Use @Volatile so checks in onAccessibilityEvent (binder thread) are visible
    @Volatile private var redShown = false
    @Volatile private var greenShown = false
    @Volatile private var alertFired = false   // prevents TTS/vibration from firing multiple times

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
        Log.i(TAG, "Service connected")
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            tts?.setLanguage(Locale.ENGLISH)
            ttsReady = true
        }
    }

    override fun onInterrupt() { removeAll() }
    override fun onDestroy() { super.onDestroy(); removeAll(); tts?.shutdown() }

    private fun startForegroundNotif() {
        val channelId = "antiscam_guard"
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(
                NotificationChannel(channelId, "AntiScam Guard", NotificationManager.IMPORTANCE_LOW).apply {
                    description = "Monitoring UPI apps for scam activity"
                }
            )
        }
        val pi = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val notif = Notification.Builder(this, channelId)
            .setContentTitle("AntiScam Guard Active").setContentText("Monitoring for UPI scams")
            .setSmallIcon(android.R.drawable.ic_lock_lock).setContentIntent(pi).setOngoing(true).build()
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

        // *** CRITICAL: If overlay is already shown, do NOT process any more events ***
        // This prevents TTS stuttering and redundant calls
        if (redShown) return

        val root = rootInActiveWindow ?: return
        val text = extractText(root).lowercase()
        if (text.isBlank()) return

        when {
            isScam(text) -> {
                Log.w(TAG, "🚨 SCAM in $pkg")
                hideGreen()
                showRed()
            }
            isSafe(text) && !greenShown -> showGreen()
        }
    }

    private fun isScam(t: String) = SCAM_A.any { t.contains(it) } && SCAM_B.any { t.contains(it) }
    private fun isSafe(t: String) = SAFE_A.any { t.contains(it) } && SAFE_B.any { t.contains(it) }

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

    private fun showRed() {
        // Double-check (volatile read) before posting to handler
        if (redShown || alertFired) return
        alertFired = true   // set IMMEDIATELY to prevent any other event from triggering
        redShown = true     // set IMMEDIATELY — don't wait for handler.post

        handler.post {
            try {
                val view = LayoutInflater.from(this).inflate(R.layout.overlay_scam, null)
                val tvCountdown = view.findViewById<TextView>(R.id.tv_countdown)

                // Use FLAG_NOT_FOCUSABLE — KEY FIX:
                // - Overlay receives ALL touch events
                // - System home/back/recents STILL WORK (they're system gestures, not touch events)
                // - No FLAG_NOT_TOUCH_MODAL (which was stealing touches on some devices)
                wm?.addView(view, overlayParams())
                redView = view

                // Attach dismiss AFTER adding to WindowManager
                setupDismiss(view, tvCountdown)

                // Fire alert ONCE
                scamAlert()
                Log.i(TAG, "Red overlay shown + alert fired")
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

    // ─── Dismiss via touch on root view ───────────────────────────────────────

    private fun setupDismiss(view: View, tvCountdown: TextView?) {
        view.setOnTouchListener { v, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    Log.d(TAG, "TOUCH DOWN — starting ${HOLD_MS}ms timer")
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
                    Log.d(TAG, "TOUCH UP — cancelling timer")
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

    // ─── Green Overlay ────────────────────────────────────────────────────────

    private fun showGreen() {
        if (greenShown) return
        greenShown = true
        handler.post {
            try {
                val view = LayoutInflater.from(this).inflate(R.layout.overlay_safe, null)
                wm?.addView(view, overlayParams())
                greenView = view
                safeAlert()
                handler.postDelayed({ hideGreen() }, 4000L)
            } catch (e: Exception) {
                Log.e(TAG, "showGreen error", e)
                greenShown = false
            }
        }
    }

    private fun hideGreen() {
        if (!greenShown) return
        handler.post {
            try { wm?.removeView(greenView); greenView = null; greenShown = false }
            catch (e: Exception) { Log.e(TAG, "hideGreen error", e) }
        }
    }

    private fun removeAll() {
        if (redShown) removeRedNow()
        hideGreen()
    }

    // ─── Window Params ────────────────────────────────────────────────────────

    private fun overlayParams(): WindowManager.LayoutParams {
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_SYSTEM_ALERT

        // FLAG_NOT_FOCUSABLE:
        //   ✅ Overlay receives touch events (unlike FLAG_NOT_TOUCHABLE)
        //   ✅ Home/Back/Recents still work (no focus = no input method = system gestures pass)
        //   ✅ No device freeze
        //   ❌ Cannot receive keyboard input (we don't need it)
        val flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON

        return WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            type, flags, PixelFormat.TRANSLUCENT
        )
    }

    // ─── Alerts (fire exactly ONCE per detection) ─────────────────────────────

    private fun scamAlert() {
        vibrate(longArrayOf(0, 500, 200, 500))
        if (ttsReady) {
            tts?.speak(
                "Warning! Scam detected. Do not enter your UPI PIN. This is a fraud request.",
                TextToSpeech.QUEUE_FLUSH, null, "SCAM"
            )
        }
    }

    private fun safeAlert() {
        vibrate(longArrayOf(0, 100))
        if (ttsReady) tts?.speak("Transaction looks safe.", TextToSpeech.QUEUE_FLUSH, null, "SAFE")
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
