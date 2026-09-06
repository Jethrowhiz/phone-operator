# Phone Operator — MVP skeleton

See `ARCHITECTURE.md` for the full data-flow diagram and design rationale.

## What's implemented in this drop

| Stage | Status |
|---|---|
| 1. App + basic UI | ✅ `MainActivity.kt`, `ui/ChatScreen.kt` |
| 2. AI communication | ✅ `ai/AnthropicAIClient.kt` — real HTTP call + JSON→`TaskPlan` parsing, wired into `MainActivity` |
| 3. Structured action format | ✅ `ai/ActionModels.kt` |
| 4. Action executor | ✅ `executor/ActionExecutor.kt` (all 22 action types from the spec have at least a working or clearly-stubbed branch) |
| 5. AccessibilityService | ✅ `accessibility/PhoneOperatorAccessibilityService.kt` + `ScreenReader.kt` |
| 6. AI decisions → executor wiring | ✅ `ui/ChatViewModel.kt` |
| 7. Voice input | ✅ `voice/VoiceInputManager.kt` (platform `SpeechRecognizer`, no custom model) |
| 8. Confirmation & security | ✅ `security/SecurityPolicy.kt`, `ConfirmationManager.kt`, `planner/ActionValidator.kt` |
| 9. Error recovery | ✅ `ChatViewModel.attemptRecovery` — on a recoverable failure, re-captures the screen and asks the AI for one corrected step or a clarifying question |
| 10. Performance pass | Not started — deferred until 1-9 are exercised on real devices |
| 11. Real-app testing | Not started |

## Building without Android Studio (GitHub Actions)

If you can't install Android Studio right now, `.github/workflows/build-apk.yml`
builds a debug APK entirely in the cloud. Push this project to a GitHub repo
(web-upload works fine, no `git` CLI required), open the **Actions** tab, wait
for the run to go green, and download `phone-operator-debug-apk` from the
run's **Artifacts** section. That zip contains `app-debug.apk` — sideload it
directly (Files app → unzip → tap the APK → allow "install unknown apps" when
prompted). No IDE, no SDK download, no cable required.

## Required setup before this builds/runs

1. Open in Android Studio (Koala+ recommended) as a normal Gradle project.
2. Run on a device/emulator. On first launch you'll see a one-time dialog
   asking for your Anthropic API key — it's written straight to
   `ApiKeyStore` (Android Keystore-backed `EncryptedSharedPreferences`),
   never to plain prefs or logs. `AnthropicAIClient` reads it from there on
   every request via `apiKeyProvider`, so swapping/rotating keys doesn't
   touch code.
3. Add an `ic_launcher` mipmap set (any placeholder icon works for local testing).
4. Enable the accessibility service when prompted:
   Settings → Accessibility → Phone Operator → toggle on. The app opens this
   screen for you on first launch if it detects the service isn't running.

## The brain / hands split, concretely

- **Brain — `ai/AnthropicAIClient.kt`**: the only class that calls the network.
  Takes the user's message + optional screen JSON + remembered aliases, sends
  them with `PromptTemplate.SYSTEM_PROMPT`, and parses the model's JSON-only
  reply into a `TaskPlan`. It has zero knowledge of Android — no Intents, no
  AccessibilityNodeInfo, nothing. If you swap providers, this is the only file
  that changes.
- **Hands — `executor/ActionExecutor.kt` + `accessibility/`**: know how to
  execute exactly one already-decided `Action` at a time. They never decide
  *what* to do next; they either report `Success` or a `Failed(recoverable=?)`.
- **The seam — `ui/ChatViewModel.kt`**: the only place brain and hands meet.
  `onUserMessage` asks the brain for a plan (or short-circuits to
  `LocalIntentParser` for trivial commands); `attemptRecovery` is what happens
  when the hands report a recoverable failure — it re-asks the brain for one
  corrected step using a fresh screen snapshot, rather than the hands guessing
  on their own or the brain ever touching the phone directly.

## Immediate next TODOs (in priority order)

1. **Contact/app alias resolution** using `MemoryStore` — remember that "John"
   maps to a specific WhatsApp contact after the first disambiguation, so the
   CONFIRM loop doesn't repeat every time.
2. **Wire the MediaProjection consent flow** for `ScreenshotCapture` — the class
   is in place but `MainActivity` doesn't yet request the one-time screen-capture
   permission; do this only when a real `find_text`/`tap_text` miss occurs.
3. **Execution history screen** — `ExecutionHistoryStore` is already logging
   every run; add a simple list UI (settings-adjacent screen) to browse it.
4. **Retry budget** — `attemptRecovery` currently allows exactly one retry per
   step (enforced by `ActionExecutor.execute`'s call site). Consider a small
   per-plan cap too, so a pathological plan can't loop through many recovery
   round-trips.
5. **Settings screen** to rotate/clear the API key (`ApiKeyStore.clear()`
   already supports it) and to view/delete `MemoryStore` entries per the
   MEMORY section of the spec.

## Design notes worth remembering while extending this

- `ActionValidator` is the trust boundary. Any new action type must be added
  to the `ActionType` enum, given an executor branch, **and** a sensitivity
  rule in `SecurityPolicy` before it's usable — there's no path for the AI
  to invoke something the validator doesn't know about.
- Keep `LocalIntentParser` additions conservative. It exists purely to avoid
  a network round-trip for a handful of high-confidence commands (alarms,
  back/home/scroll). Anything ambiguous belongs with the AI, not here.
- Every network call in the entire codebase should live behind `AIClient`.
  If you find yourself calling `fetch`/`OkHttp` anywhere else, that's a sign
  something drifted from the intended architecture.
