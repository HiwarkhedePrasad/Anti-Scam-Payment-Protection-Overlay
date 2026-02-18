package com.example.antiscam

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.speech.tts.TextToSpeech
import android.util.Log
import android.view.GestureDetector
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import java.util.Locale

class ScamDetectorService : AccessibilityService(), TextToSpeech.OnInitListener {

    companion object {
        private const val TAG = "ScamDetector"

        // ── FR-02: Package filter ──────────────────────────────────────────────
        // Set to TRUE to monitor ALL apps (recommended for hackathon demo).
        // Set to FALSE to restrict to UPI apps only (production mode).
        private const val MONITOR_ALL_APPS = true

        private val MONITORED_PACKAGES = setOf(
            "com.phonepe.app",
            "com.phonepe.app.preprod",
            "com.google.android.apps.nbu.paisa.user",   // Google Pay
            "net.one97.paytm",
            "in.org.npci.upiapp",                        // BHIM
            "com.amazon.mShop.android.shopping",         // Amazon Pay
            "com.mobikwik_new",
            "com.freecharge.android",
            "com.axis.mobile",
            "com.sbi.lotusintouch",
            "com.dreamplug.androidapp",                  // CRED
            "com.whatsapp",                              // WhatsApp Pay
        )

        // ── Scam detection keywords ───────────────────────────────────────────
        // A scam is detected when ANY keyword from GROUP_A appears together
        // with ANY keyword from GROUP_B on the same screen.
        private val SCAM_KEYWORDS_GROUP_A = listOf(
            "request from",
            "collect request",
            "payment request",
            "requesting money",
            "pay to",
        )
        private val SCAM_KEYWORDS_GROUP_B = listOf(
            "enter upi pin",
            "enter pin",
            "upi pin",
            "confirm with pin",
            "authenticate",
        )

        // ── Safe transaction keywords (FR-07/08) ──────────────────────────────
        private val SAFE_KEYWORDS_A = listOf("paying", "pay to", "sending")
        private val SAFE_KEYWORDS_B = listOf("₹", "rs.", "inr")
    }

    private var windowManager: WindowManager? = null
    private var redOverlayView: View? = null
    private var greenOverlayView: View? = null
    private var isRedOverlayShown = false
    private var isGreenOverlayShown = false

    private var tts: TextToSpeech? = null
    private var isTtsReady = false

    private val handler = Handler(Looper.getMainLooper())

    override fun onServiceConnected() {
        super.onServiceConnected()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        tts = TextToSpeech(this, this)
        Log.i(TAG, "✅ ScamDetectorService connected. MONITOR_ALL_APPS=$MONITOR_ALL_APPS")
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            val result = tts?.setLanguage(Locale.ENGLISH)
            if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                Log.w(TAG, "TTS language not supported, using default")
            }
            isTtsReady = true
            Log.i(TAG, "✅ TTS initialized")
        } else {
            Log.e(TAG, "❌ TTS init failed")
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return

        val packageName = event.packageName?.toString() ?: return

        // Skip our own app to avoid feedback loops
        if (packageName == "com.example.antiscam") return

        // FR-02: Package filter (bypassed in demo mode)
        if (!MONITOR_ALL_APPS && packageName !in MONITORED_PACKAGES) return

        if (event.eventType != AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED &&
            event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
        ) return

        val rootNode = rootInActiveWindow ?: return
        val screenContent = extractAllText(rootNode).lowercase()

        // Log every scan so we can debug via logcat
        if (screenContent.isNotBlank()) {
            Log.d(TAG, "[$packageName] Scanned: ${screenContent.take(300)}")
        }

        when {
            isScamDetected(screenContent) -> {
                Log.w(TAG, "🚨 SCAM DETECTED in $packageName!")
                hideGreenOverlay()
                showRedOverlay()
            }
            isSafeTransaction(screenContent) && !isRedOverlayShown -> {
                showGreenOverlay()
            }
            else -> {
                // If we navigated away from the scam screen, hide green overlay.
                // Keep red overlay up until user long-presses to dismiss.
                if (!isRedOverlayShown) hideGreenOverlay()
            }
        }
    }

    // ─── Detection Logic ──────────────────────────────────────────────────────

    private fun isScamDetected(content: String): Boolean {
        val hasGroupA = SCAM_KEYWORDS_GROUP_A.any { content.contains(it) }
        val hasGroupB = SCAM_KEYWORDS_GROUP_B.any { content.contains(it) }
        if (hasGroupA && hasGroupB) {
            Log.w(TAG, "Scam match — GroupA: ${SCAM_KEYWORDS_GROUP_A.filter { content.contains(it) }}, GroupB: ${SCAM_KEYWORDS_GROUP_B.filter { content.contains(it) }}")
        }
        return hasGroupA && hasGroupB
    }

    private fun isSafeTransaction(content: String): Boolean {
        val hasA = SAFE_KEYWORDS_A.any { content.contains(it) }
        val hasB = SAFE_KEYWORDS_B.any { content.contains(it) }
        return hasA && hasB
    }

    private fun extractAllText(node: AccessibilityNodeInfo?): String {
        if (node == null) return ""
        val sb = StringBuilder()
        traverseNode(node, sb)
        return sb.toString()
    }

    private fun traverseNode(node: AccessibilityNodeInfo?, sb: StringBuilder) {
        if (node == null) return
        node.text?.let { sb.append(it).append(" ") }
        node.contentDescription?.let { sb.append(it).append(" ") }
        node.hintText?.let { sb.append(it).append(" ") }
        for (i in 0 until node.childCount) {
            traverseNode(node.getChild(i), sb)
        }
    }

    // ─── Red (Scam) Overlay ───────────────────────────────────────────────────

    private fun showRedOverlay() {
        if (isRedOverlayShown) return
        handler.post {
            try {
                val params = buildOverlayParams()
                redOverlayView = LayoutInflater.from(this).inflate(R.layout.overlay_scam, null)
                setupLongPressDismiss(redOverlayView!!) { hideRedOverlay() }
                windowManager?.addView(redOverlayView, params)
                isRedOverlayShown = true
                triggerScamAlert()
            } catch (e: Exception) {
                Log.e(TAG, "Error showing red overlay", e)
            }
        }
    }

    private fun hideRedOverlay() {
        if (!isRedOverlayShown) return
        handler.post {
            try {
                windowManager?.removeView(redOverlayView)
                redOverlayView = null
                isRedOverlayShown = false
                Log.i(TAG, "Red overlay dismissed")
            } catch (e: Exception) {
                Log.e(TAG, "Error hiding red overlay", e)
            }
        }
    }

    // ─── Green (Safe) Overlay ─────────────────────────────────────────────────

    private fun showGreenOverlay() {
        if (isGreenOverlayShown) return
        handler.post {
            try {
                val params = buildOverlayParams()
                greenOverlayView = LayoutInflater.from(this).inflate(R.layout.overlay_safe, null)
                windowManager?.addView(greenOverlayView, params)
                isGreenOverlayShown = true
                triggerSafeAlert()
                handler.postDelayed({ hideGreenOverlay() }, 4000L)
            } catch (e: Exception) {
                Log.e(TAG, "Error showing green overlay", e)
            }
        }
    }

    private fun hideGreenOverlay() {
        if (!isGreenOverlayShown) return
        handler.post {
            try {
                windowManager?.removeView(greenOverlayView)
                greenOverlayView = null
                isGreenOverlayShown = false
            } catch (e: Exception) {
                Log.e(TAG, "Error hiding green overlay", e)
            }
        }
    }

    // ─── WindowManager Params ─────────────────────────────────────────────────

    private fun buildOverlayParams(): WindowManager.LayoutParams {
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_SYSTEM_ALERT

        // CRITICAL: Do NOT add FLAG_NOT_TOUCHABLE or FLAG_NOT_FOCUSABLE.
        // Without those flags, the overlay intercepts and consumes ALL touch events.
        // FLAG_LAYOUT_IN_SCREEN ensures it covers the full screen including status bar.
        val flags = WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON

        return WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            type,
            flags,
            PixelFormat.TRANSLUCENT
        )
    }

    // ─── FR-06: Long-Press Dismiss (3 seconds) ────────────────────────────────

    private fun setupLongPressDismiss(view: View, onDismiss: () -> Unit) {
        val gestureDetector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onLongPress(e: MotionEvent) {
                Log.i(TAG, "Long press — dismissing overlay")
                onDismiss()
            }
        })
        view.setOnTouchListener { _, event ->
            gestureDetector.onTouchEvent(event)
            true // consume ALL touches
        }
    }

    // ─── FR-09: TTS + FR-10: Vibration ───────────────────────────────────────

    private fun triggerScamAlert() {
        vibrate(longArrayOf(0, 300, 200, 300, 200, 600))
        if (isTtsReady) {
            tts?.speak(
                "Warning! Scam detected. Do not enter your U P I PIN. This is a fraud request.",
                TextToSpeech.QUEUE_FLUSH, null, "SCAM_ALERT"
            )
        }
    }

    private fun triggerSafeAlert() {
        vibrate(longArrayOf(0, 100))
        if (isTtsReady) {
            tts?.speak("Transaction looks safe.", TextToSpeech.QUEUE_FLUSH, null, "SAFE_ALERT")
        }
    }

    private fun vibrate(pattern: LongArray) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vm = getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
                vm.defaultVibrator.vibrate(VibrationEffect.createWaveform(pattern, -1))
            } else {
                @Suppress("DEPRECATION")
                val v = getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    v.vibrate(VibrationEffect.createWaveform(pattern, -1))
                } else {
                    @Suppress("DEPRECATION")
                    v.vibrate(pattern, -1)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Vibration error", e)
        }
    }

    // ─── Lifecycle ────────────────────────────────────────────────────────────

    override fun onInterrupt() {
        hideRedOverlay()
        hideGreenOverlay()
    }

    override fun onDestroy() {
        super.onDestroy()
        hideRedOverlay()
        hideGreenOverlay()
        tts?.stop()
        tts?.shutdown()
    }
}
