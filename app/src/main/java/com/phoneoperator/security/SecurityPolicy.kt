package com.phoneoperator.security

import com.phoneoperator.ai.Action
import com.phoneoperator.ai.ActionType

/**
 * Central definition of what counts as "sensitive" — kept as one small,
 * auditable object rather than scattered checks. If product/security wants
 * to change policy, this is the only file that should need editing.
 */
object SecurityPolicy {

    /** Packages the app is allowed to launch/target without extra scrutiny. */
    val trustedPackagePrefixes = listOf(
        "com.whatsapp", "com.google.android", "com.android",
        "com.instagram.android", "com.spotify.music", "com.brave.browser"
        // extend as needed; unknown packages are still allowed but logged.
    )

    val maxStepsPerPlan = 25
    val maxTypeTextLength = 2000

    /**
     * A step is sensitive if the action type is inherently risky, OR its
     * text/target content signals something risky (e.g. tap_text("Send") in
     * a messaging context, or a settings page that changes security state).
     */
    fun isSensitive(action: Action): Boolean {
        return when (action.type) {
            ActionType.NOTIFICATION -> false
            ActionType.TAP_TEXT, ActionType.TAP -> {
                val t = action.text?.lowercase().orEmpty()
                t in setOf("send", "delete", "buy now", "pay", "confirm purchase",
                    "transfer", "post", "share publicly", "uninstall", "install")
            }
            ActionType.OPEN_SETTINGS -> action.settingsPage?.lowercase() in setOf(
                "security", "password", "accounts", "permissions", "apps"
            )
            else -> false
        }
    }

    fun isKnownActionType(raw: String): Boolean =
        runCatching { ActionType.valueOf(raw) }.isSuccess
}
