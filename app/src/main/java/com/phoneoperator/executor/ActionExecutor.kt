package com.phoneoperator.executor

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.AlarmClock
import android.provider.Settings
import android.util.DisplayMetrics
import android.view.WindowManager
import android.view.accessibility.AccessibilityNodeInfo
import com.phoneoperator.accessibility.PhoneOperatorAccessibilityService
import com.phoneoperator.accessibility.ScreenReader
import com.phoneoperator.ai.Action
import com.phoneoperator.ai.ActionType
import com.phoneoperator.ai.StepResult
import com.phoneoperator.ai.TaskPlan
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * Executes one validated plan, step by step, emitting StepResult as it goes
 * so the UI can show ✓/✗ live. Stops immediately if `stopRequested` flips
 * true (wired to the emergency Stop button) or a step fails and isn't
 * recoverable.
 */
class ActionExecutor(private val context: Context) {

    @Volatile private var stopRequested = false
    fun requestStop() { stopRequested = true }

    /** Real device dimensions, used for scroll gestures instead of a guessed constant. */
    private fun screenSize(): Pair<Int, Int> {
        val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        wm.defaultDisplay.getRealMetrics(metrics)
        return metrics.widthPixels to metrics.heightPixels
    }

    /**
     * @param onRecoverableFailure invoked when a step fails but is flagged
     *   recoverable. Receives the failed action + reason and returns a
     *   *replacement* action to retry, or null to give up and surface the
     *   failure as-is. This is how the "brain" gets a second chance: the
     *   caller (ChatViewModel) typically re-captures the screen and asks the
     *   AI for a corrected single step here, rather than the executor ever
     *   talking to the network itself.
     */
    suspend fun execute(
        plan: TaskPlan,
        onStep: (StepResult) -> Unit,
        onRecoverableFailure: (suspend (Action, String) -> Action?)? = null
    ) {
        stopRequested = false

        // Special-cased local task that never goes through gesture automation.
        if (plan.task == "set_alarm") {
            executeSetAlarm(plan, onStep)
            return
        }

        for (action in plan.steps) {
            if (stopRequested) {
                onStep(StepResult.Failed(action, "Stopped by user", recoverable = false))
                return
            }
            var result = executeSingle(action)

            if (result is StepResult.Failed && result.recoverable && onRecoverableFailure != null) {
                val replacement = onRecoverableFailure(result.action, result.reason)
                if (replacement != null && !stopRequested) {
                    result = executeSingle(replacement)
                }
            }

            onStep(result)
            if (result is StepResult.Failed && !result.recoverable) return
        }
    }

    private fun executeSetAlarm(plan: TaskPlan, onStep: (StepResult) -> Unit) {
        val raw = plan.steps.firstOrNull()?.text ?: return
        val (hourStr, rest) = raw.split(":").let { it[0] to it.getOrElse(1) { "00" } }
        var hour = hourStr.toIntOrNull() ?: return
        val isPm = rest.lowercase().contains("pm")
        val minute = rest.filter { it.isDigit() }.toIntOrNull() ?: 0
        if (isPm && hour < 12) hour += 12
        val intent = Intent(AlarmClock.ACTION_SET_ALARM).apply {
            putExtra(AlarmClock.EXTRA_HOUR, hour)
            putExtra(AlarmClock.EXTRA_MINUTES, minute)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        runCatching { context.startActivity(intent) }
            .onSuccess { onStep(StepResult.Success(plan.steps.first())) }
            .onFailure { onStep(StepResult.Failed(plan.steps.first(), "No alarm app available", recoverable = false)) }
    }

    private suspend fun executeSingle(action: Action): StepResult {
        val service = PhoneOperatorAccessibilityService.instance
        return try {
            when (action.type) {
                ActionType.OPEN_APP -> openApp(action)
                ActionType.CLOSE_APP -> closeApp(action) // best-effort: go home; Android restricts force-stopping other apps
                ActionType.GO_BACK -> { service?.performGlobalBack(); StepResult.Success(action) }
                ActionType.HOME -> { service?.performGlobalHome(); StepResult.Success(action) }
                ActionType.OPEN_URL -> openUrl(action)
                ActionType.OPEN_SETTINGS -> openSettings(action)
                ActionType.WAIT -> { delay(action.durationMs ?: 500); StepResult.Success(action) }
                ActionType.READ_SCREEN -> readScreen(service, action)
                ActionType.FIND_TEXT -> findText(service, action)
                ActionType.TAP_TEXT -> tapText(service, action)
                ActionType.TAP -> tapCoordinates(service, action)
                ActionType.TYPE_TEXT -> typeText(service, action)
                ActionType.CLEAR_TEXT -> clearText(service, action)
                ActionType.SCROLL_DOWN, ActionType.SCROLL_UP -> scroll(service, action)
                ActionType.SWIPE -> swipe(service, action)
                ActionType.LONG_PRESS -> longPress(service, action)
                ActionType.PRESS_ENTER -> pressEnter(service, action)
                ActionType.COPY, ActionType.PASTE -> clipboardAction(service, action)
                ActionType.TAKE_SCREENSHOT -> {
                    // TODO(stage 9): wire to MediaProjection-based capture; fallback path only.
                    StepResult.Failed(action, "Screenshot fallback not yet implemented", recoverable = true)
                }
                ActionType.NOTIFICATION -> StepResult.Success(action) // handled by UI layer
                ActionType.CONFIRM -> StepResult.Success(action) // handled by ConfirmationManager upstream
            }
        } catch (e: Exception) {
            StepResult.Failed(action, e.message ?: "Unknown error", recoverable = true)
        }
    }

    private fun openApp(action: Action): StepResult {
        val pkg = action.packageName ?: return StepResult.Failed(action, "No package specified", false)
        val launchIntent = context.packageManager.getLaunchIntentForPackage(pkg)
            ?: return StepResult.Failed(action, "App not installed: $pkg", recoverable = false)
        launchIntent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
        context.startActivity(launchIntent)
        return StepResult.Success(action)
    }

    private fun closeApp(action: Action): StepResult {
        PhoneOperatorAccessibilityService.instance?.performGlobalHome()
        return StepResult.Success(action)
    }

    private fun openUrl(action: Action): StepResult {
        val url = action.url ?: return StepResult.Failed(action, "No URL specified", false)
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        context.startActivity(intent)
        return StepResult.Success(action)
    }

    private fun openSettings(action: Action): StepResult {
        val page = action.settingsPage?.lowercase()
        val settingsAction = when (page) {
            "wifi" -> Settings.ACTION_WIFI_SETTINGS
            "bluetooth" -> Settings.ACTION_BLUETOOTH_SETTINGS
            "sound" -> Settings.ACTION_SOUND_SETTINGS
            "display" -> Settings.ACTION_DISPLAY_SETTINGS
            "apps" -> Settings.ACTION_APPLICATION_SETTINGS
            "security" -> Settings.ACTION_SECURITY_SETTINGS
            "accounts" -> Settings.ACTION_SYNC_SETTINGS
            "permissions" -> Settings.ACTION_APPLICATION_SETTINGS
            else -> Settings.ACTION_SETTINGS
        }
        context.startActivity(Intent(settingsAction).apply { flags = Intent.FLAG_ACTIVITY_NEW_TASK })
        return StepResult.Success(action)
    }

    private fun readScreen(service: PhoneOperatorAccessibilityService?, action: Action): StepResult {
        if (service == null) return StepResult.Failed(action, "Accessibility service not connected", false)
        ScreenReader.capture(service) // caller (planner) reads this via a shared channel; kept simple here
        return StepResult.Success(action)
    }

    private fun findText(service: PhoneOperatorAccessibilityService?, action: Action): StepResult {
        if (service == null) return StepResult.Failed(action, "Accessibility service not connected", false)
        val query = action.text ?: return StepResult.Failed(action, "No search text given", false)
        val snapshot = ScreenReader.capture(service)
        val matches = ScreenReader.findByText(snapshot, query)
        return if (matches.isNotEmpty()) StepResult.Success(action)
        else StepResult.Failed(action, "No element matching '$query' found on screen", recoverable = true)
    }

    private fun tapText(service: PhoneOperatorAccessibilityService?, action: Action): StepResult {
        if (service == null) return StepResult.Failed(action, "Accessibility service not connected", false)
        val query = action.text ?: return StepResult.Failed(action, "No tap target given", false)
        val root = service.currentRoot() ?: return StepResult.Failed(action, "No active window", true)
        val node = findClickableNode(root, query)
            ?: return StepResult.Failed(action, "Couldn't find a tappable '$query'", recoverable = true)
        val success = node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        return if (success) StepResult.Success(action)
        else StepResult.Failed(action, "Tap on '$query' failed", recoverable = true)
    }

    private fun findClickableNode(root: AccessibilityNodeInfo, query: String): AccessibilityNodeInfo? {
        val q = query.lowercase()
        val nodes = root.findAccessibilityNodeInfosByText(query)
        // Prefer an exact, clickable node; fall back to nearest clickable ancestor.
        for (n in nodes) {
            var cur: AccessibilityNodeInfo? = n
            var depth = 0
            while (cur != null && depth < 5) {
                if (cur.isClickable) return cur
                cur = cur.parent
                depth++
            }
        }
        return null
    }

    private suspend fun tapCoordinates(service: PhoneOperatorAccessibilityService?, action: Action): StepResult {
        if (service == null) return StepResult.Failed(action, "Accessibility service not connected", false)
        val x = action.x?.toFloat() ?: return StepResult.Failed(action, "No x coordinate", false)
        val y = action.y?.toFloat() ?: return StepResult.Failed(action, "No y coordinate", false)
        return suspendCancellableCoroutine { cont ->
            service.tapAt(x, y) { ok ->
                cont.resume(if (ok) StepResult.Success(action) else StepResult.Failed(action, "Tap gesture cancelled", true))
            }
        }
    }

    private fun typeText(service: PhoneOperatorAccessibilityService?, action: Action): StepResult {
        if (service == null) return StepResult.Failed(action, "Accessibility service not connected", false)
        val text = action.text ?: return StepResult.Failed(action, "No text to type", false)
        val root = service.currentRoot() ?: return StepResult.Failed(action, "No active window", true)
        val target = findFocusedOrFirstEditable(root)
            ?: return StepResult.Failed(action, "No editable field focused", recoverable = true)
        val args = android.os.Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        val success = target.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
        return if (success) StepResult.Success(action)
        else StepResult.Failed(action, "Typing failed", recoverable = true)
    }

    private fun clearText(service: PhoneOperatorAccessibilityService?, action: Action): StepResult {
        if (service == null) return StepResult.Failed(action, "Accessibility service not connected", false)
        val root = service.currentRoot() ?: return StepResult.Failed(action, "No active window", true)
        val target = findFocusedOrFirstEditable(root)
            ?: return StepResult.Failed(action, "No editable field focused", recoverable = true)
        val args = android.os.Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, "")
        }
        target.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
        return StepResult.Success(action)
    }

    private fun findFocusedOrFirstEditable(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)?.let { return it }
        fun search(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
            if (node.isEditable) return node
            for (i in 0 until node.childCount) {
                node.getChild(i)?.let { child ->
                    search(child)?.let { return it }
                    child.recycle()
                }
            }
            return null
        }
        return search(root)
    }

    private suspend fun scroll(service: PhoneOperatorAccessibilityService?, action: Action): StepResult {
        if (service == null) return StepResult.Failed(action, "Accessibility service not connected", false)
        val (width, height) = screenSize()
        val midX = width / 2f
        val topY = height * 0.25f
        val bottomY = height * 0.75f
        val (y1, y2) = if (action.type == ActionType.SCROLL_DOWN) bottomY to topY else topY to bottomY
        return suspendCancellableCoroutine { cont ->
            service.swipe(midX, y1, midX, y2, 250) { ok ->
                cont.resume(if (ok) StepResult.Success(action) else StepResult.Failed(action, "Scroll gesture failed", true))
            }
        }
    }

    private suspend fun swipe(service: PhoneOperatorAccessibilityService?, action: Action): StepResult {
        if (service == null) return StepResult.Failed(action, "Accessibility service not connected", false)
        val x1 = action.x?.toFloat() ?: return StepResult.Failed(action, "Missing x", false)
        val y1 = action.y?.toFloat() ?: return StepResult.Failed(action, "Missing y", false)
        val x2 = action.x2?.toFloat() ?: return StepResult.Failed(action, "Missing x2", false)
        val y2 = action.y2?.toFloat() ?: return StepResult.Failed(action, "Missing y2", false)
        return suspendCancellableCoroutine { cont ->
            service.swipe(x1, y1, x2, y2, action.durationMs ?: 300) { ok ->
                cont.resume(if (ok) StepResult.Success(action) else StepResult.Failed(action, "Swipe failed", true))
            }
        }
    }

    private suspend fun longPress(service: PhoneOperatorAccessibilityService?, action: Action): StepResult {
        if (service == null) return StepResult.Failed(action, "Accessibility service not connected", false)
        val x = action.x?.toFloat() ?: return StepResult.Failed(action, "Missing x", false)
        val y = action.y?.toFloat() ?: return StepResult.Failed(action, "Missing y", false)
        return suspendCancellableCoroutine { cont ->
            service.longPressAt(x, y, action.durationMs ?: 600) { ok ->
                cont.resume(if (ok) StepResult.Success(action) else StepResult.Failed(action, "Long press failed", true))
            }
        }
    }

    private fun pressEnter(service: PhoneOperatorAccessibilityService?, action: Action): StepResult {
        if (service == null) return StepResult.Failed(action, "Accessibility service not connected", false)
        val root = service.currentRoot() ?: return StepResult.Failed(action, "No active window", true)
        val target = findFocusedOrFirstEditable(root)
            ?: return StepResult.Failed(action, "No editable field focused", recoverable = true)
        val success = target.performAction(AccessibilityNodeInfo.ACTION_IME_ENTER)
        return if (success) StepResult.Success(action)
        else StepResult.Failed(action, "Enter key press failed", recoverable = true)
    }

    private fun clipboardAction(service: PhoneOperatorAccessibilityService?, action: Action): StepResult {
        if (service == null) return StepResult.Failed(action, "Accessibility service not connected", false)
        val root = service.currentRoot() ?: return StepResult.Failed(action, "No active window", true)
        val target = findFocusedOrFirstEditable(root) ?: root
        val nodeAction = if (action.type == ActionType.COPY) AccessibilityNodeInfo.ACTION_COPY
        else AccessibilityNodeInfo.ACTION_PASTE
        val success = target.performAction(nodeAction)
        return if (success) StepResult.Success(action)
        else StepResult.Failed(action, "${action.type} failed", recoverable = true)
    }
}
