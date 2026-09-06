package com.phoneoperator.security

import com.phoneoperator.ai.Action
import kotlinx.coroutines.CompletableDeferred

/**
 * Bridges the executor (which runs on a background coroutine) with the UI
 * (which shows a Cancel/Confirm dialog). The executor calls `request` and
 * suspends until the user answers via `resolve`.
 */
class ConfirmationManager {

    data class PendingConfirmation(val action: Action, val deferred: CompletableDeferred<Boolean>)

    private var pending: PendingConfirmation? = null
    var onNewConfirmationRequest: ((Action) -> Unit)? = null

    suspend fun request(action: Action): Boolean {
        val deferred = CompletableDeferred<Boolean>()
        pending = PendingConfirmation(action, deferred)
        onNewConfirmationRequest?.invoke(action)
        return deferred.await()
    }

    /** Called by the UI when the user taps Confirm or Cancel. */
    fun resolve(approved: Boolean) {
        pending?.deferred?.complete(approved)
        pending = null
    }
}
