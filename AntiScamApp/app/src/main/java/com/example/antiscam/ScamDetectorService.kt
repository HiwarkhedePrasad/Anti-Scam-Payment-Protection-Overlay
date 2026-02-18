package com.example.antiscam

import android.accessibilityservice.AccessibilityService
import android.graphics.PixelFormat
import android.os.Build
import android.util.Log
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

class ScamDetectorService : AccessibilityService() {

    private var windowManager: WindowManager? = null
    private var overlayView: View? = null
    private var isOverlayShown = false

    override fun onServiceConnected() {
        super.onServiceConnected()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        
        // We are interested in content changes and state changes
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED ||
            event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            
            val rootNode = rootInActiveWindow ?: return
            
            // Check for scam patterns
            if (checkForScam(rootNode)) {
                showOverlay()
            } else {
                // Optional: Remove overlay if condition is no longer met? 
                // Creating a persistent block is safer for the demo requirement "block touch inputs"
                // but for usability we might want to hide it if they leave the scam app.
                // For this request: "inflate immediately upon detection"
                // I will add a method to hide it if we want to be smarter, but primarily show it.
            }
        }
    }

    private fun checkForScam(rootNode: AccessibilityNodeInfo): Boolean {
        val allText = StringBuilder()
        traverseNode(rootNode, allText)
        val screenContent = allText.toString()

        val hasRequestFrom = screenContent.contains("Request from", ignoreCase = true)
        val hasEnterPin = screenContent.contains("Enter UPI PIN", ignoreCase = true)

        Log.d("ScamDetector", "Content: $screenContent | Match: $hasRequestFrom && $hasEnterPin")

        return hasRequestFrom && hasEnterPin
    }

    private fun traverseNode(node: AccessibilityNodeInfo?, sb: StringBuilder) {
        if (node == null) return

        if (node.text != null) {
            sb.append(node.text).append(" ")
        }
        
        // Also check content description
        if (node.contentDescription != null) {
            sb.append(node.contentDescription).append(" ")
        }

        for (i in 0 until node.childCount) {
            traverseNode(node.getChild(i), sb)
        }
    }

    private fun showOverlay() {
        if (isOverlayShown) return

        if (windowManager == null) return

        try {
            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                else
                    WindowManager.LayoutParams.TYPE_PHONE,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                        WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS, // Try FLAG_NOT_FOCUSABLE first, but requirement says "block touch inputs"
                PixelFormat.TRANSLUCENT
            )
            
            // To block touch inputs, we actually want it to be focusable or consume events.
            // FLAG_NOT_TOUCH_MODAL allow events to pass through? No. 
            // We want to consume everything. 
            params.flags = WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or 
                           WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                           WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH
            
            // Important for blocking:
            // We are creating a full screen view. If we don't set FLAG_NOT_TOUCHABLE, it consumes touches.

            overlayView = LayoutInflater.from(this).inflate(R.layout.overlay_layout, null)
            
            // Add a click listener to the overlay specifically to consume clicks or potentially dismiss for testing
            overlayView?.setOnClickListener { 
                // Consume click. Do nothing. Or maybe log it.
                Log.d("ScamDetector", "Blocked touch input on overlay")
            }

            windowManager?.addView(overlayView, params)
            isOverlayShown = true
        } catch (e: Exception) {
            Log.e("ScamDetector", "Error showing overlay", e)
        }
    }

    // Helper to remove overlay (e.g. on service destroy or if we want to implement logic to hide it)
    private fun removeOverlay() {
        if (isOverlayShown && overlayView != null && windowManager != null) {
            try {
                windowManager?.removeView(overlayView)
                isOverlayShown = false
                overlayView = null
            } catch (e: Exception) {
                Log.e("ScamDetector", "Error removing overlay", e)
            }
        }
    }

    override fun onInterrupt() {
        removeOverlay()
    }
    
    override fun onDestroy() {
        super.onDestroy()
        removeOverlay()
    }
}
