package com.phoneoperator.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.phoneoperator.accessibility.PhoneOperatorAccessibilityService
import com.phoneoperator.accessibility.ScreenReader
import com.phoneoperator.ai.AIClient
import com.phoneoperator.ai.StepResult
import com.phoneoperator.ai.ValidationResult
import com.phoneoperator.executor.ActionExecutor
import com.phoneoperator.memory.ExecutionHistoryStore
import com.phoneoperator.memory.ExecutionLogEntry
import com.phoneoperator.memory.MemoryStore
import com.phoneoperator.planner.ActionValidator
import com.phoneoperator.planner.LocalIntentParser
import com.phoneoperator.security.ConfirmationManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

sealed class ChatEntry {
    data class UserMessage(val text: String) : ChatEntry()
    data class AiMessage(val text: String) : ChatEntry()
    data class ExecutionStep(val label: String, val status: StepStatus) : ChatEntry()
}

enum class StepStatus { RUNNING, DONE, FAILED }

data class ChatUiState(
    val entries: List<ChatEntry> = emptyList(),
    val isExecuting: Boolean = false,
    val pendingConfirmationText: String? = null
)

class ChatViewModel(
    private val aiClient: AIClient,
    private val executor: ActionExecutor,
    private val memoryStore: MemoryStore,
    private val confirmationManager: ConfirmationManager,
    private val historyStore: ExecutionHistoryStore
) : ViewModel() {

    private val _uiState = MutableStateFlow(ChatUiState())
    val uiState: StateFlow<ChatUiState> = _uiState

    init {
        confirmationManager.onNewConfirmationRequest = { action ->
            val summary = action.text ?: action.packageName ?: action.settingsPage ?: "this action"
            _uiState.value = _uiState.value.copy(pendingConfirmationText = summary)
        }
    }

    fun onUserMessage(text: String) {
        appendEntry(ChatEntry.UserMessage(text))

        val localPlan = LocalIntentParser.tryParse(text)
        if (localPlan != null) {
            runPlanFlow(localPlan.summaryForUser ?: "On it.") {
                com.phoneoperator.ai.ValidationResult.Approved(localPlan, requiresConfirmation = false)
            }
            return
        }

        viewModelScope.launch {
            val screenContext = PhoneOperatorAccessibilityService.instance?.let {
                ScreenReader.toJson(ScreenReader.capture(it))
            }
            val result = aiClient.planTask(
                userMessage = text,
                screenContext = screenContext,
                memoryContext = memoryStore.asContextJson()
            )
            result.onSuccess { plan ->
                runPlanFlow(plan.summaryForUser ?: "On it.") { ActionValidator.validate(plan) }
            }.onFailure { err ->
                appendEntry(ChatEntry.AiMessage("Sorry, I couldn't reach the AI service: ${err.message}"))
            }
        }
    }

    private fun runPlanFlow(summary: String, validate: () -> ValidationResult) {
        when (val result = validate()) {
            is ValidationResult.Rejected -> {
                appendEntry(ChatEntry.AiMessage("I couldn't safely run that: ${result.reason}"))
            }
            is ValidationResult.Approved -> {
                appendEntry(ChatEntry.AiMessage(summary))
                viewModelScope.launch {
                    _uiState.value = _uiState.value.copy(isExecuting = true)
                    var hadUnrecoverableFailure = false
                    executor.execute(
                        plan = result.plan,
                        onStep = { stepResult ->
                            val entry = when (stepResult) {
                                is StepResult.Success -> ChatEntry.ExecutionStep(
                                    label = describeAction(stepResult.action), status = StepStatus.DONE
                                )
                                is StepResult.Failed -> {
                                    if (!stepResult.recoverable) hadUnrecoverableFailure = true
                                    ChatEntry.ExecutionStep(
                                        label = "${describeAction(stepResult.action)} — ${stepResult.reason}",
                                        status = StepStatus.FAILED
                                    )
                                }
                            }
                            appendEntry(entry)
                        },
                        onRecoverableFailure = { failedAction, reason -> attemptRecovery(failedAction, reason) }
                    )
                    historyStore.log(
                        ExecutionLogEntry(
                            timestampMs = System.currentTimeMillis(),
                            task = result.plan.task,
                            summary = summary,
                            outcome = if (hadUnrecoverableFailure) "failed" else "completed"
                        )
                    )
                    _uiState.value = _uiState.value.copy(isExecuting = false)
                }
            }
        }
    }

    /**
     * Called once by the executor when a step fails recoverably. Re-captures
     * the live screen, hands the AI the failure + fresh context, and expects
     * back a short corrected plan. If the AI's plan is just a CONFIRM/question
     * step (e.g. "which John do you mean?"), surface that to the user as a
     * message instead of silently retrying — matching the ERROR RECOVERY spec.
     */
    private suspend fun attemptRecovery(
        failedAction: com.phoneoperator.ai.Action,
        reason: String
    ): com.phoneoperator.ai.Action? {
        val screenContext = PhoneOperatorAccessibilityService.instance?.let {
            ScreenReader.toJson(ScreenReader.capture(it))
        }
        val recoveryPrompt = buildString {
            append("The previous action failed.\n")
            append("Failed action: ${failedAction.type}")
            failedAction.text?.let { append(" (target text: '$it')") }
            append("\nReason: $reason\n")
            append("Given the current screen, return ONE corrected next step to accomplish the ")
            append("same intent, or a single CONFIRM step asking the user to clarify if the ")
            append("situation is ambiguous (e.g. multiple matching contacts).")
        }

        val result = aiClient.planTask(
            userMessage = recoveryPrompt,
            screenContext = screenContext,
            memoryContext = memoryStore.asContextJson()
        )

        val plan = result.getOrNull() ?: return null
        val nextStep = plan.steps.firstOrNull() ?: return null

        if (nextStep.type == com.phoneoperator.ai.ActionType.CONFIRM) {
            appendEntry(ChatEntry.AiMessage(nextStep.text ?: plan.summaryForUser ?: "Could you clarify what you meant?"))
            return null
        }

        return nextStep
    }

    fun stopExecution() {
        executor.requestStop()
        _uiState.value = _uiState.value.copy(isExecuting = false)
        historyStore.log(
            ExecutionLogEntry(
                timestampMs = System.currentTimeMillis(),
                task = "user_stop",
                summary = "Execution stopped by user",
                outcome = "stopped"
            )
        )
    }

    fun confirmPendingAction(approved: Boolean) {
        confirmationManager.resolve(approved)
        _uiState.value = _uiState.value.copy(pendingConfirmationText = null)
    }

    private fun describeAction(action: com.phoneoperator.ai.Action): String = when (action.type) {
        com.phoneoperator.ai.ActionType.OPEN_APP -> "Opening ${action.packageName}"
        com.phoneoperator.ai.ActionType.TAP_TEXT -> "Tapping '${action.text}'"
        com.phoneoperator.ai.ActionType.TYPE_TEXT -> "Typing message"
        com.phoneoperator.ai.ActionType.GO_BACK -> "Going back"
        com.phoneoperator.ai.ActionType.HOME -> "Going home"
        com.phoneoperator.ai.ActionType.SCROLL_DOWN -> "Scrolling down"
        com.phoneoperator.ai.ActionType.SCROLL_UP -> "Scrolling up"
        com.phoneoperator.ai.ActionType.OPEN_URL -> "Opening link"
        com.phoneoperator.ai.ActionType.OPEN_SETTINGS -> "Opening settings"
        else -> action.type.name.lowercase().replace("_", " ")
    }

    private fun appendEntry(entry: ChatEntry) {
        _uiState.value = _uiState.value.copy(entries = _uiState.value.entries + entry)
    }
}
