# Phone Operator — Architecture

## Data flow

```
User (text/voice)
      │
      ▼
ChatViewModel  ──────────────►  LocalIntentParser
      │                              │ (handles trivial, offline-safe
      │                              │  commands like "set alarm 7am"
      │                              │  without ever calling the API)
      │                              ▼
      │                         ActionExecutor ──► AccessibilityService ──► Phone
      │
      │ (everything else)
      ▼
AIClient (network) ──► AI API (structured-output prompt)
      │
      ▼
TaskPlan { steps: [Action] }
      │
      ▼
ActionValidator  (checks package allow-list, sensitive-action rules,
      │            malformed/oversized plans, rate limiting)
      ▼
ConfirmationManager (pauses plan if any step is "sensitive")
      │
      ▼
ActionExecutor ──► AccessibilityService ──► Phone
      │
      ▼
ExecutionLog (shown in chat as ✓ ticks, saved for history/undo context)
```

## Module responsibilities

| Module | Responsibility | Talks to network? |
|---|---|---|
| `ui` | Chat screen, mic button, execution status strip, Stop button | no |
| `ai` | `AIClient`, request/response models, prompt template | yes |
| `planner` | `ActionValidator`, `LocalIntentParser`, plan diffing for error recovery | no |
| `executor` | `ActionExecutor` — turns one `Action` into a real system call | no |
| `accessibility` | `PhoneOperatorAccessibilityService`, `ScreenReader` (UI tree → structured JSON) | no |
| `memory` | `MemoryStore` — small key/value + contact/app aliases, user-editable | no |
| `voice` | Wrapper around `SpeechRecognizer` (Android platform STT) | no |
| `security` | `ConfirmationManager`, allow-lists, permission checks, execution logs | no |

Only `ai` ever touches the network for reasoning. Everything else is local,
which is what keeps the app small and fast.

## The contract between AI and phone

The AI **never** executes anything. It returns JSON matching `TaskPlan`
(see `ai/ActionModels.kt`). `ActionValidator` is the trust boundary: it
treats every plan as untrusted input, same as if it came from a stranger.

Rules enforced there:
- Only whitelisted action types are accepted (unknown action → plan rejected, ask user).
- `open_app` / `open_url` package or URL must not be empty and is checked against a
  simple block-list (no `market://`, no raw `intent:` URIs that could reflect side effects).
- Any plan containing a sensitive action gets a **paused** status; the executor
  will not proceed past that step without an explicit user confirmation event.
- Plans longer than a max step count are rejected outright (protects against a
  runaway or malformed AI response looping actions).
- `type_text` payloads are size-capped and never auto-logged in plaintext execution
  history if flagged as containing sensitive-looking content (e.g. matches a password field).

## Why AccessibilityService and not screenshots-by-default

`ScreenReader` pulls the accessibility node tree (text, class name, bounds,
clickable flag, content-description) into a compact JSON snapshot. This is:
- cheaper (no image, no vision model call),
- more reliable for tapping ("tap the element whose text == 'Send'" is exact,
  not a guess from pixels),
- faster (no round trip through an image-capable model).

Screenshots (`take_screenshot`) are only invoked when `ScreenReader` returns
no usable match for a `find_text`/`tap_text` step — that's the fallback path,
not the default.

## Stages mapped to this skeleton

This first drop implements the skeleton for **Stages 1–6**:
chat UI, AI client interface, action/plan data models + validator, the
executor with the initial action vocabulary, the AccessibilityService, and
the wiring between planner → executor. Voice (7), confirmation UI polish (8),
recovery heuristics (9), perf pass (10) and real-app testing (11) are stubbed
with clear TODOs so they can be built incrementally on top of this without
re-architecting.
