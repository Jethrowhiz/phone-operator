package com.phoneoperator.planner

import com.phoneoperator.ai.TaskPlan
import com.phoneoperator.ai.ValidationResult
import com.phoneoperator.security.SecurityPolicy

/**
 * Treats every TaskPlan as untrusted input, even though it came from "our"
 * AI client. This is the one place that decides a plan is safe to run.
 */
object ActionValidator {

    fun validate(plan: TaskPlan): ValidationResult {
        if (plan.steps.isEmpty()) {
            return ValidationResult.Rejected("Empty plan")
        }
        if (plan.steps.size > SecurityPolicy.maxStepsPerPlan) {
            return ValidationResult.Rejected(
                "Plan has ${plan.steps.size} steps, exceeds max of ${SecurityPolicy.maxStepsPerPlan}"
            )
        }
        for (step in plan.steps) {
            step.text?.let {
                if (it.length > SecurityPolicy.maxTypeTextLength) {
                    return ValidationResult.Rejected("A step's text exceeds max length")
                }
            }
            step.url?.let {
                if (!it.startsWith("http://") && !it.startsWith("https://")) {
                    return ValidationResult.Rejected("Rejected non-http(s) URL: $it")
                }
            }
        }

        val requiresConfirmation = plan.steps.any { SecurityPolicy.isSensitive(it) }
        return ValidationResult.Approved(plan, requiresConfirmation)
    }
}
