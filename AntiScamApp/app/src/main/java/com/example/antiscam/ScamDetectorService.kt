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
        private const val LONG_PRESS_DURATION_MS = 3000L // 3-second long press to dismiss

        // FR-02: Package filtering — only monitor these UPI apps
        private val MONITORED_PACKAGES = setOf(
            "com.phonepe.app",
            "com.google.android.apps.nbu.paisa.user", // Google Pay
            "net.one97.paytm",
            "in.org.npci.upiapp",
            "com.amazon.mShop.android.shopping", // Amazon Pay
            "com.mobikwik_new",
            "com.freecharge.android"
        )

        // Scam detection keywords
        private const val KEYWORD_REQUEST = "request from"
        private const val KEYWORD_PIN = "enter upi pin"

        // Safe transaction keywords (FR-07/08)
        private const val KEYWORD_PAYING = "paying"
        private const val KEYWORD_AMOUNT = "₹"
    }

    private var windowManager: WindowManager? = null
    private var redOverlayView: View? = null
    private var greenOverlayView: View? = null
    private var isRedOverlayShown = false
    private var isGreenOverlayShown = false

    private var tts: TextToSpeech? = null
    private var isTtsReady = false

    private val handler = Handler(Looper.getMainLooper())
    private var longPressRunnable: Runnable? = null

    override fun onServiceConnected() {
        super.onServiceConnected()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        tts = TextToSpeech(this, this)
        Log.i(TAG, "ScamDetectorService connected. Monitoring: $MONITORED_PACKAGES")
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            tts?.language = Locale("hi", "IN") // Hindi for Indian UPI context; fallback to English
            val result = tts?.setLanguage(Locale("hi", "IN"))
            if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                tts?.language = Locale.ENGLISH
            }
            isTtsReady = true
            Log.i(TAG, "TTS initialized successfully")
        } else {
            Log.e(TAG, "TTS initialization failed")
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return

        // FR-02: Only process events from monitored UPI apps
        val packageName = event.packageName?.toString() ?: return
        if (packageName !in MONITORED_PACKAGES) return

        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED ||
            event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
        ) {
            val rootNode = rootInActiveWindow ?: return

            val screenContent = extractAllText(rootNode)
            Log.d(TAG, "[$packageName] Screen content: ${screenContent.take(200)}")

            when {
                isScamDetected(screenContent) -> {
                    hideGreenOverlay()
                    showRedOverlay()
                }
                isSafeTransaction(screenContent) -> {
                    hideRedOverlay()
                    showGreenOverlay()
                }
                else -> {
                    // Screen changed to something neutral — hide both overlays
                    hideRedOverlay()
                    hideGreenOverlay()
                }
            }
        }
    }

    // ─── Detection Logic ──────────────────────────────────────────────────────

    private fun isScamDetected(content: String): Boolean {
        val lower = content.lowercase()
        return lower.contains(KEYWORD_REQUEST) && lower.contains(KEYWORD_PIN)
    }

    private fun isSafeTransaction(content: String): Boolean {
        // FR-07/08: Detect legitimate self-initiated payment flows
        val lower = content.lowercase()
        return lower.contains(KEYWORD_PAYING) && lower.contains(KEYWORD_AMOUNT)
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
                Log.w(TAG, "🔴 SCAM OVERLAY SHOWN")
            } catch (e: Exception) {
                Log.e(TAG, "Error showing red overlay", e)
            }
        }
    }

    private fun hideRedOverlay() {
        if (!isRedOverlayShown) return
        handler.post {
            try {
                longPressRunnable?.let { handler.removeCallbacks(it) }
                windowManager?.removeView(redOverlayView)
                redOverlayView = null
                isRedOverlayShown = false
                Log.i(TAG, "🔴 Red overlay dismissed")
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
                // Green overlay auto-dismisses after 4 seconds
                handler.postDelayed({ hideGreenOverlay() }, 4000L)
                windowManager?.addView(greenOverlayView, params)
                isGreenOverlayShown = true
                triggerSafeAlert()
                Log.i(TAG, "🟢 SAFE OVERLAY SHOWN")
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

        // FR-05 FIX: Do NOT set FLAG_NOT_TOUCHABLE or FLAG_NOT_FOCUSABLE.
        // Without those flags the overlay consumes ALL touch events — nothing passes through.
        // FLAG_LAYOUT_IN_SCREEN ensures it covers status bar area.
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

    // ─── FR-06: Long-Press Dismiss ────────────────────────────────────────────

    private fun setupLongPressDismiss(view: View, onDismiss: () -> Unit) {
        val gestureDetector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onLongPress(e: MotionEvent) {
                Log.i(TAG, "Long press detected — dismissing overlay")
                onDismiss()
            }
        })

        view.setOnTouchListener { v, event ->
            gestureDetector.onTouchEvent(event)
            true // consume all touches — nothing passes through
        }
    }

    // ─── FR-09: TTS Warning ───────────────────────────────────────────────────

    private fun triggerScamAlert() {
        vibrate(longArrayOf(0, 300, 200, 300, 200, 600))
        if (isTtsReady) {
            tts?.speak(
                "Warning! Scam detected. Do not enter your UPI PIN. This is a fraud request.",
                TextToSpeech.QUEUE_FLUSH,
                null,
                "SCAM_ALERT"
            )
        }
    }

    private fun triggerSafeAlert() {
        vibrate(longArrayOf(0, 100))
        if (isTtsReady) {
            tts?.speak(
                "Transaction looks safe. Proceed carefully.",
                TextToSpeech.QUEUE_FLUSH,
                null,
                "SAFE_ALERT"
            )
        }
    }

    // ─── FR-10: Vibration ─────────────────────────────────────────────────────

    private fun vibrate(pattern: LongArray) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vm = getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
                vm.defaultVibrator.vibrate(VibrationEffect.createWaveform(pattern, -1))
            } else {
                @Suppress("DEPRECATION")
                val vibrator = getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    vibrator.vibrate(VibrationEffect.createWaveform(pattern, -1))
                } else {
                    @Suppress("DEPRECATION")
                    vibrator.vibrate(pattern, -1)
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
