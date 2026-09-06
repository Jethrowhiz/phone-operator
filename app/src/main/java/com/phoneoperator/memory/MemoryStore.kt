package com.phoneoperator.memory

import android.content.Context
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
data class MemoryEntry(val key: String, val value: String, val category: String)

/**
 * Deliberately tiny: a flat, human-readable list of key/value facts, backed
 * by SharedPreferences (no database needed at this scale). Fully visible
 * and editable/deletable by the user — never a hidden profile.
 *
 * Categories used: "preferred_app", "contact_alias", "frequent_command".
 */
class MemoryStore(context: Context) {

    private val prefs = context.getSharedPreferences("phone_operator_memory", Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true }

    fun all(): List<MemoryEntry> {
        val raw = prefs.getString("entries", "[]") ?: "[]"
        return runCatching { json.decodeFromString<List<MemoryEntry>>(raw) }.getOrDefault(emptyList())
    }

    fun remember(entry: MemoryEntry) {
        val updated = all().filterNot { it.key == entry.key && it.category == entry.category } + entry
        save(updated)
    }

    fun forget(key: String, category: String) {
        save(all().filterNot { it.key == key && it.category == category })
    }

    fun clearAll() = prefs.edit().clear().apply()

    /** Compact JSON blob to hand to AIClient as context, capped in size deliberately. */
    fun asContextJson(maxEntries: Int = 30): String =
        json.encodeToString(all().takeLast(maxEntries))

    private fun save(entries: List<MemoryEntry>) {
        prefs.edit().putString("entries", json.encodeToString(entries)).apply()
    }
}
