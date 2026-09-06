package com.phoneoperator.ai

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * The concrete "brain". Everything phone-side (executor, accessibility,
 * validator) is dumb by design — this is the only class that reasons about
 * what the user wants. It knows nothing about how to *do* anything; it only
 * ever returns a TaskPlan for the validator to check and the executor to run.
 *
 * Swap ANTHROPIC_API_URL/model or the whole class if you use a different
 * provider — nothing else in the app depends on this implementation, only
 * on the AIClient interface.
 */
class AnthropicAIClient(
    private val apiKeyProvider: () -> String,
    private val model: String = "claude-sonnet-4-5"
) : AIClient {

    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    override suspend fun planTask(
        userMessage: String,
        screenContext: String?,
        memoryContext: String?
    ): Result<TaskPlan> = withContext(Dispatchers.IO) {
        try {
            val userContent = buildString {
                append(userMessage)
                if (!memoryContext.isNullOrBlank() && memoryContext != "[]") {
                    append("\n\n[Remembered preferences/aliases]\n").append(memoryContext)
                }
                if (!screenContext.isNullOrBlank()) {
                    append("\n\n[Current screen contents]\n").append(screenContext)
                }
            }

            val body = JSONObject().apply {
                put("model", model)
                put("max_tokens", 1024)
                put("system", PromptTemplate.SYSTEM_PROMPT)
                put("messages", JSONArray().put(
                    JSONObject().put("role", "user").put("content", userContent)
                ))
            }

            val request = Request.Builder()
                .url("https://api.anthropic.com/v1/messages")
                .addHeader("x-api-key", apiKeyProvider())
                .addHeader("anthropic-version", "2023-06-01")
                .addHeader("content-type", "application/json")
                .post(body.toString().toRequestBody("application/json".toMediaType()))
                .build()

            http.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    return@withContext Result.failure(
                        IllegalStateException("AI API error ${response.code}: ${response.body?.string()}")
                    )
                }
                val raw = response.body?.string()
                    ?: return@withContext Result.failure(IllegalStateException("Empty AI response"))
                val text = extractAssistantText(raw)
                    ?: return@withContext Result.failure(IllegalStateException("No text block in AI response"))
                val plan = parsePlan(text)
                Result.success(plan)
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /** Anthropic responses put text in content[].text blocks; concatenate any of type "text". */
    private fun extractAssistantText(rawJson: String): String? {
        val root = JSONObject(rawJson)
        val content = root.optJSONArray("content") ?: return null
        val sb = StringBuilder()
        for (i in 0 until content.length()) {
            val block = content.getJSONObject(i)
            if (block.optString("type") == "text") sb.append(block.optString("text"))
        }
        return sb.toString().ifBlank { null }
    }

    /** The model is instructed to emit ONLY JSON, but strip stray fences defensively. */
    private fun parsePlan(modelText: String): TaskPlan {
        val cleaned = modelText.trim()
            .removePrefix("```json").removePrefix("```")
            .removeSuffix("```")
            .trim()
        return json.decodeFromString(TaskPlan.serializer(), cleaned)
    }
}
