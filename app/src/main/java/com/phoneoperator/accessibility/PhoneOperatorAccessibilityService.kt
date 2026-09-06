package com.phoneoperator.accessibility

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/**
 * The only component with system-level power to read the screen and inject
 * gestures. Everything upstream (AI, planner, executor) is just producing
 * requests; this class is the last line of enforcement, so keep it small
 * and defensive — never trust an incoming request blindly, re-check bounds
 * and node validity here too.
 */
class PhoneOperatorAccessibilityService : AccessibilityService() {

    companion object {
        // Executor holds a nullable weak-ish reference via a static instance;
        // simplest possible pattern for a single-service app. Swap for a
        // proper binder/EventBus if the app grows multiple consumers.
        @Volatile var instance: PhoneOperatorAccessibilityService? = null
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    override fun onDestroy() {
        super.onDestroy()
        instance = null
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Intentionally minimal: we pull the node tree on-demand via
        // rootInActiveWindow rather than reacting to every event, to avoid
        // constant background work (see PERFORMANCE section of the spec).
    }

    override fun onInterrupt() { /* no-op */ }

    // ---- Primitive operations used by ActionExecutor ----

    fun currentRoot(): AccessibilityNodeInfo? = rootInActiveWindow

    fun performGlobalBack() = performGlobalAction(GLOBAL_ACTION_BACK)
    fun performGlobalHome() = performGlobalAction(GLOBAL_ACTION_HOME)
    fun performGlobalRecents() = performGlobalAction(GLOBAL_ACTION_RECENTS)

    /** Tap by exact screen coordinates, used when we resolved a node's bounds. */
    fun tapAt(x: Float, y: Float, onDone: (Boolean) -> Unit) {
        val path = Path().apply { moveTo(x, y) }
        val stroke = GestureDescription.StrokeDescription(path, 0, 60)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        dispatchGesture(gesture, object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) = onDone(true)
            override fun onCancelled(gestureDescription: GestureDescription?) = onDone(false)
        }, null)
    }

    fun swipe(x1: Float, y1: Float, x2: Float, y2: Float, durationMs: Long, onDone: (Boolean) -> Unit) {
        val path = Path().apply { moveTo(x1, y1); lineTo(x2, y2) }
        val stroke = GestureDescription.StrokeDescription(path, 0, durationMs)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        dispatchGesture(gesture, object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) = onDone(true)
            override fun onCancelled(gestureDescription: GestureDescription?) = onDone(false)
        }, null)
    }

    fun longPressAt(x: Float, y: Float, durationMs: Long, onDone: (Boolean) -> Unit) {
        val path = Path().apply { moveTo(x, y) }
        val stroke = GestureDescription.StrokeDescription(path, 0, durationMs)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        dispatchGesture(gesture, object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) = onDone(true)
            override fun onCancelled(gestureDescription: GestureDescription?) = onDone(false)
        }, null)
    }
}
