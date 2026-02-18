package com.example.antiscam

import android.accessibilityservice.AccessibilityService
import android.content.Context
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
        private const val HOLD_MS = 2000L          // hold duration to dismiss
        private const val COOLDOWN_MS = 20_000L    // suppress re-detection after dismiss (20s)

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
    private var redShown = false
    private var greenShown = false

    private var tts: TextToSpeech? = null
    private var ttsReady = false

    private val handler = Handler(Looper.getMainLooper())
    private var dismissRunnable: Runnable? = null
    private var dismissedAt = 0L

    // ─── Lifecycle ────────────────────────────────────────────────────────────

    override fun onServiceConnected() {
        super.onServiceConnected()
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        tts = TextToSpeech(this, this)
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

    // ─── Event Handling ───────────────────────────────────────────────────────

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        val pkg = event.packageName?.toString() ?: return
        if (pkg == "com.example.antiscam") return
        if (!MONITOR_ALL_APPS && pkg !in MONITORED_PACKAGES) return
        if (event.eventType != AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED &&
            event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return

        // Cooldown: don't re-detect right after user dismissed
        if (SystemClock.elapsedRealtime() - dismissedAt < COOLDOWN_MS) return

        val text = extractText(rootInActiveWindow).lowercase()
        if (text.isBlank()) return
        Log.d(TAG, "[$pkg] ${text.take(200)}")

        when {
            isScam(text) -> { hideGreen(); showRed() }
            isSafe(text) && !redShown -> showGreen()
            else -> if (!redShown) hideGreen()
        }
    }

    private fun isScam(t: String) = SCAM_A.any { t.contains(it) } && SCAM_B.any { t.contains(it) }
    private fun isSafe(t: String) = SAFE_A.any { t.contains(it) } && SAFE_B.any { t.contains(it) }

    private fun extractText(node: AccessibilityNodeInfo?): String {
        if (node == null) return ""
        val sb = StringBuilder()
        fun walk(n: AccessibilityNodeInfo?) {
            if (n == null) return
            n.text?.let { sb.append(it).append(' ') }
            n.contentDescription?.let { sb.append(it).append(' ') }
            n.hintText?.let { sb.append(it).append(' ') }
            for (i in 0 until n.childCount) walk(n.getChild(i))
        }
        walk(node)
        return sb.toString()
    }

    // ─── Red Overlay ──────────────────────────────────────────────────────────

    private fun showRed() {
        if (redShown) return
        handler.post {
            try {
                val view = LayoutInflater.from(this).inflate(R.layout.overlay_scam, null)
                val tvCountdown = view.findViewById<TextView>(R.id.tv_countdown)

                // Attach hold-to-dismiss to the ROOT view — no Button needed
                attachHoldToDismiss(view, tvCountdown)

                wm?.addView(view, overlayParams())
                redView = view
                redShown = true
                scamAlert()
                Log.i(TAG, "Red overlay shown")
            } catch (e: Exception) {
                Log.e(TAG, "showRed error", e)
            }
        }
    }

    private fun hideRed() {
        if (!redShown) return
        handler.post { removeRedNow() }
    }

    // Called directly from dismissRunnable (already on main thread) — no handler.post needed
    private fun removeRedNow() {
        try {
            dismissRunnable?.let { handler.removeCallbacks(it) }
            dismissRunnable = null
            if (redView != null) {
                wm?.removeView(redView)
                redView = null
            }
            redShown = false
            dismissedAt = SystemClock.elapsedRealtime()
            Log.i(TAG, "Red overlay removed — 20s cooldown started")
        } catch (e: Exception) {
            Log.e(TAG, "removeRedNow error", e)
        }
    }

    // ─── Green Overlay ────────────────────────────────────────────────────────

    private fun showGreen() {
        if (greenShown) return
        handler.post {
            try {
                val view = LayoutInflater.from(this).inflate(R.layout.overlay_safe, null)
                wm?.addView(view, overlayParams())
                greenView = view
                greenShown = true
                safeAlert()
                handler.postDelayed({ hideGreen() }, 4000L)
            } catch (e: Exception) {
                Log.e(TAG, "showGreen error", e)
            }
        }
    }

    private fun hideGreen() {
        if (!greenShown) return
        handler.post {
            try {
                wm?.removeView(greenView)
                greenView = null
                greenShown = false
            } catch (e: Exception) {
                Log.e(TAG, "hideGreen error", e)
            }
        }
    }

    private fun removeAll() { hideRed(); hideGreen() }

    // ─── Hold-to-Dismiss ──────────────────────────────────────────────────────
    // Attached to the ROOT view. No Button involved — avoids all touch dispatch issues.
    // ACTION_DOWN → start HOLD_MS timer → fires hideRed()
    // ACTION_UP / CANCEL → cancel timer, reset label

    private fun attachHoldToDismiss(rootView: View, tvCountdown: TextView?) {
        var tickRunnable: Runnable? = null

        rootView.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    Log.d(TAG, "Touch DOWN — starting hold timer")
                    var remaining = (HOLD_MS / 1000).toInt()
                    tvCountdown?.text = "Hold $remaining..."

                    // Tick every second to update label
                    tickRunnable = object : Runnable {
                        override fun run() {
                            remaining--
                            if (remaining > 0) {
                                tvCountdown?.text = "Hold $remaining..."
                                handler.postDelayed(this, 1000L)
                            }
                        }
                    }
                    handler.postDelayed(tickRunnable!!, 1000L)

                    // Fire dismiss after HOLD_MS — runs on main thread, call removeRedNow() directly
                    dismissRunnable = Runnable {
                        Log.i(TAG, "Hold complete — dismissing")
                        tickRunnable?.let { handler.removeCallbacks(it) }
                        removeRedNow()
                    }
                    handler.postDelayed(dismissRunnable!!, HOLD_MS)
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    Log.d(TAG, "Touch UP/CANCEL — cancelling hold")
                    tickRunnable?.let { handler.removeCallbacks(it) }
                    dismissRunnable?.let { handler.removeCallbacks(it) }
                    dismissRunnable = null
                    tvCountdown?.text = "Hold anywhere to dismiss"
                    true
                }
                else -> true
            }
        }
    }

    // ─── WindowManager Params ─────────────────────────────────────────────────

    private fun overlayParams(): WindowManager.LayoutParams {
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_SYSTEM_ALERT

        // FLAG_NOT_TOUCH_MODAL: system nav (Home/Back) still works — device can't freeze
        // No FLAG_NOT_TOUCHABLE: overlay consumes touches within its bounds
        val flags = WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
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
        vibrate(longArrayOf(0, 300, 200, 300, 200, 600))
        if (ttsReady) tts?.speak(
            "Warning! Scam detected. Do not enter your U P I PIN.",
            TextToSpeech.QUEUE_FLUSH, null, "SCAM"
        )
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
