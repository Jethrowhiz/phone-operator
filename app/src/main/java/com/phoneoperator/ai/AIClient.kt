package com.phoneoperator.ai

/**
 * The single point of contact with the reasoning API. Everything else in
 * the app is offline-capable. Swap the implementation (different provider,
 * different endpoint) without touching planner/executor code.
 */
interface AIClient {
    /**
     * @param userMessage what the user typed or said (already transcribed).
     * @param screenContext optional compact JSON from ScreenReader, included
     *   only when the request plausibly needs current-screen context
     *   (keeps token usage down for simple commands).
     * @param memoryContext small JSON blob of remembered preferences/aliases.
     */
    suspend fun planTask(
        userMessage: String,
        screenContext: String? = null,
        memoryContext: String? = null
    ): Result<TaskPlan>
}

/**
 * System prompt template. Kept in one place so it's easy to tune.
 * The model is instructed to return ONLY JSON matching TaskPlan — no prose,
 * no markdown fences — so the app can parse it directly.
 */
object PromptTemplate {
    const val SYSTEM_PROMPT = """
You are the reasoning engine for "Phone Operator", an Android automation
assistant. You never execute anything yourself. You respond ONLY with a
single JSON object matching this schema, and nothing else:

{
  "task": string,
  "summaryForUser": string,
  "steps": [
    {
      "type": one of [OPEN_APP, CLOSE_APP, GO_BACK, HOME, TAP, TAP_TEXT,
                       FIND_TEXT, TYPE_TEXT, CLEAR_TEXT, SCROLL_UP,
                       SCROLL_DOWN, SWIPE, LONG_PRESS, PRESS_ENTER, COPY,
                       PASTE, WAIT, READ_SCREEN, TAKE_SCREENSHOT, OPEN_URL,
                       OPEN_SETTINGS, NOTIFICATION, CONFIRM],
      "packageName": string|null,
      "text": string|null,
      "settingsPage": string|null,
      "url": string|null,
      "durationMs": number|null
    }
  ]
}

Rules:
- Break multi-step requests (e.g. "find my latest photo and send it to Sarah")
  into the smallest reliable sequence of steps above.
- Never invent a package name you are not confident about; if unsure, use
  FIND_TEXT/TAP_TEXT navigation from the home screen instead of OPEN_APP.
- Do not include explanations, markdown, or any text outside the JSON object.
- If the request is ambiguous (e.g. multiple contacts named "John"), return a
  short plan that ends in a CONFIRM step asking the user to disambiguate,
  rather than guessing.
"""
}
