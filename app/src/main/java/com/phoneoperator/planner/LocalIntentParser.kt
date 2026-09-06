package com.phoneoperator.planner

import com.phoneoperator.ai.Action
import com.phoneoperator.ai.ActionType
import com.phoneoperator.ai.TaskPlan
import java.util.regex.Pattern

/**
 * Handles the subset of commands that are cheap and reliable to parse
 * locally, so they never need a network round-trip. Returns null if the
 * command doesn't match a known local pattern — caller should fall back to
 * AIClient in that case.
 *
 * Keep this list SMALL and high-confidence only. When in doubt, return null
 * and let the AI plan it; a wrong local guess is worse than one extra API call.
 */
object LocalIntentParser {

    private val alarmPattern = Pattern.compile(
        "set (an )?alarm for (\\d{1,2})(:(\\d{2}))?\\s*(am|pm)?", Pattern.CASE_INSENSITIVE
    )
    private val goBackPattern = Pattern.compile("go back", Pattern.CASE_INSENSITIVE)
    private val homePattern = Pattern.compile("(go( to)? home|home screen)", Pattern.CASE_INSENSITIVE)
    private val scrollDownPattern = Pattern.compile("scroll down", Pattern.CASE_INSENSITIVE)
    private val scrollUpPattern = Pattern.compile("scroll up", Pattern.CASE_INSENSITIVE)

    fun tryParse(input: String): TaskPlan? {
        val trimmed = input.trim()

        goBackPattern.matcher(trimmed).takeIf { it.find() }?.let {
            return TaskPlan("go_back", listOf(Action(ActionType.GO_BACK)), "Going back.")
        }
        homePattern.matcher(trimmed).takeIf { it.find() }?.let {
            return TaskPlan("home", listOf(Action(ActionType.HOME)), "Going home.")
        }
        scrollDownPattern.matcher(trimmed).takeIf { it.find() }?.let {
            return TaskPlan("scroll_down", listOf(Action(ActionType.SCROLL_DOWN)), "Scrolling down.")
        }
        scrollUpPattern.matcher(trimmed).takeIf { it.find() }?.let {
            return TaskPlan("scroll_up", listOf(Action(ActionType.SCROLL_UP)), "Scrolling up.")
        }

        val alarmMatcher = alarmPattern.matcher(trimmed)
        if (alarmMatcher.find()) {
            // Delegate the actual AlarmClock intent construction to the executor;
            // here we just hand it the raw hour/minute/ampm it parsed.
            val hour = alarmMatcher.group(2)
            val minute = alarmMatcher.group(4) ?: "00"
            val ampm = alarmMatcher.group(5) ?: ""
            return TaskPlan(
                task = "set_alarm",
                steps = listOf(
                    Action(
                        type = ActionType.NOTIFICATION, // placeholder marker; executor special-cases "set_alarm" task
                        text = "$hour:$minute$ampm"
                    )
                ),
                summaryForUser = "Setting an alarm for $hour:$minute $ampm".trim()
            )
        }

        return null
    }
}
