package com.phoneoperator.memory

import android.content.Context
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
data class ExecutionLogEntry(
    val timestampMs: Long,
    val task: String,
    val summary: String,
    val outcome: String // "completed" | "failed" | "stopped"
)

/**
 * Small, capped, human-readable log — not a general-purpose database. Lets
 * the user see what the agent actually did without digging through Android
 * logcat, and gives the recovery loop nothing more than it needs.
 */
class ExecutionHistoryStore(context: Context) {

    private val prefs = context.getSharedPreferences("phone_operator_history", Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true }
    private val maxEntries = 100

    fun log(entry: ExecutionLogEntry) {
        val updated = (all() + entry).takeLast(maxEntries)
        prefs.edit().putString("log", json.encodeToString(updated)).apply()
    }

    fun all(): List<ExecutionLogEntry> {
        val raw = prefs.getString("log", "[]") ?: "[]"
        return runCatching { json.decodeFromString<List<ExecutionLogEntry>>(raw) }.getOrDefault(emptyList())
    }

    fun clear() = prefs.edit().remove("log").apply()
}
