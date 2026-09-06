package com.phoneoperator.ai

import kotlinx.serialization.Serializable

/**
 * The full, fixed vocabulary of actions the executor understands.
 * Deliberately small — see ARCHITECTURE.md. Adding a new value here
 * requires adding a matching branch in ActionExecutor AND deciding
 * whether it's a SENSITIVE action in SecurityPolicy.
 */
enum class ActionType {
    OPEN_APP, CLOSE_APP, GO_BACK, HOME,
    TAP, TAP_TEXT, FIND_TEXT,
    TYPE_TEXT, CLEAR_TEXT,
    SCROLL_UP, SCROLL_DOWN, SWIPE, LONG_PRESS, PRESS_ENTER,
    COPY, PASTE, WAIT,
    READ_SCREEN, TAKE_SCREENSHOT,
    OPEN_URL, OPEN_SETTINGS,
    NOTIFICATION, CONFIRM
}

/**
 * One step in a plan. Only the fields relevant to `type` are expected to be
 * populated; the rest are null. Kept flat (vs. a sealed class per action) so
 * it maps 1:1 onto the JSON schema we hand the AI model.
 */
@Serializable
data class Action(
    val type: ActionType,
    val packageName: String? = null,   // open_app / close_app
    val text: String? = null,          // find_text / tap_text / type_text / notification
    val settingsPage: String? = null,  // open_settings, e.g. "wifi", "bluetooth"
    val url: String? = null,           // open_url
    val x: Int? = null,                // tap / long_press / swipe start
    val y: Int? = null,
    val x2: Int? = null,               // swipe end
    val y2: Int? = null,
    val durationMs: Long? = null,      // wait / long_press
    val elementIndex: Int? = null      // disambiguation when find_text matches >1 element
)

@Serializable
data class TaskPlan(
    val task: String,          // short human label, e.g. "send_message"
    val steps: List<Action>,
    val summaryForUser: String? = null // one-line "I can do that" style summary
)

/** Result of validating a plan before anything is executed. */
sealed class ValidationResult {
    data class Approved(val plan: TaskPlan, val requiresConfirmation: Boolean) : ValidationResult()
    data class Rejected(val reason: String) : ValidationResult()
}

/** Outcome of executing a single step, used to drive the ✓/✗ execution strip and recovery. */
sealed class StepResult {
    data class Success(val action: Action) : StepResult()
    data class Failed(val action: Action, val reason: String, val recoverable: Boolean) : StepResult()
}
