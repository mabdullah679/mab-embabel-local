# Implementation Plan: Product Defects and UX Hardening

## Current System Assessment

Validated on March 12, 2026 against the live local stack after `python scripts/deploy.py bootstrap`.

What is working now:

- Deployment bootstrap completed successfully on this host.
- `tools-app` and `orchestrator-app` both came up healthy.
- Runtime model selection is real, persisted in backend state, and observed through `GET /api/state`.
- The orchestrator is reading the active generation model from `tools-app`, so model switching is shared across planner/final-answer behavior.
- Mixed-signal prompts like `Draft an email to Joe ... meeting at 2pm` no longer default to unsupported based on current code.
- Event ID updates by UUID route to calendar correctly.

What is still broken in the product:

- Email generation still invents facts.
- Final user-facing answers still overstate or rephrase beyond backend truth.
- Email clarification follow-ups still fall out of scope.
- Title/date/time calendar follow-ups still fail to bind to the intended item.
- Recipient and participant matching still over-matches and duplicates contacts.
- Reminder/task creation still persists as `MEETING` in common planner payload shapes.
- Email rewrite/update flows are still brittle and often ask for unnecessary clarification or report a rewrite that was not actually persisted.

What I did not reproduce as a current live defect:

- The older startup-order/bootstrap failures described in `external-user-ux-hiccups.md`.
- The old mixed-family `unsupported` failure for email-with-meeting phrasing.
- A frontend-only indefinite hang through direct API testing. The frontend code now has timeout/stop/slow states, but I did not perform a visual browser pass.

## Verified Defects Ordered By Severity

### Must-Fix Product Issues

1. Response grounding is still unsafe for both email drafts and final answers.
   - Observed symptom:
     - `Draft an email to Joe about tomorrow's check-in. My name is John Doe.` persisted a draft with subject `Check-in Tomorrow`, but the final answer claimed `Reminder: Tomorrow's Check-in at 10 AM`.
     - The body invented `10 AM` even though the user never provided a time.
     - A prior persisted draft still contains placeholders like `[specific time and platform]` and `[Your Name]`.
     - Calendar create/update answers restated backend results conversationally instead of staying tightly bound to persisted fields.
   - Root-cause hypothesis:
     - [PlannerAgentService.java](/C:/Users/muham/Documents/mab-embabel-local/orchestrator-app/src/main/java/com/mab/orchestrator/service/PlannerAgentService.java) `addGeneratedEmailContent(...)` asks the model to write a draft with no hard grounding constraints.
     - [PlannerAgentService.java](/C:/Users/muham/Documents/mab-embabel-local/orchestrator-app/src/main/java/com/mab/orchestrator/service/PlannerAgentService.java) `finalizeAnswer(...)` asks for a free-form concise answer from `Backend result` and does not require field-preserving output.
     - [PlannerAgentService.java](/C:/Users/muham/Documents/mab-embabel-local/orchestrator-app/src/main/java/com/mab/orchestrator/service/PlannerAgentService.java) `summarizeExecutionResult(...)` emits prose instead of a structured grounded response contract.

2. Email clarification continuity is broken for short follow-up replies.
   - Observed symptom:
     - `Draft an email to Joe about tomorrow's check-in.` returned `Who should the sender be for that email?`
     - Follow-up `John Doe.` routed to `unsupported` instead of continuing the email workflow.
   - Root-cause hypothesis:
     - [PlannerAgentService.java](/C:/Users/muham/Documents/mab-embabel-local/orchestrator-app/src/main/java/com/mab/orchestrator/service/PlannerAgentService.java) `looksLikeNameOnlyResponse(...)` checks `NAME_ONLY_PATTERN` against lowercased input, so `John Doe.` becomes `john doe.` and fails the uppercase-only regex.
     - Continuation recovery depends on raw assistant text in history instead of a structured pending clarification state.

3. Calendar follow-up disambiguation by title/date/time is still brittle.
   - Observed symptom:
     - `Change the task type from meeting to reminder.` correctly entered calendar update flow but returned clarification.
     - Follow-up `the one titled "Project Sync" for today 4 pm` still failed with `I couldn't identify which calendar item to update.`
   - Root-cause hypothesis:
     - [PlannerAgentService.java](/C:/Users/muham/Documents/mab-embabel-local/orchestrator-app/src/main/java/com/mab/orchestrator/service/PlannerAgentService.java) accepts planner outputs where `targetLookup` is sometimes a string marker like `"titleLike"` instead of a populated object.
     - [PlannerExecutionService.java](/C:/Users/muham/Documents/mab-embabel-local/tools-app/src/main/java/com/mab/tools/service/PlannerExecutionService.java) `matchCalendarItems(...)` only uses `targetEntityId` for UUIDs and ignores non-UUID title references stored there.
     - [PlannerExecutionService.java](/C:/Users/muham/Documents/mab-embabel-local/tools-app/src/main/java/com/mab/tools/service/PlannerExecutionService.java) does not merge title/date/time clues from malformed planner outputs into a normalized lookup contract before matching.

4. Calendar item type intent is still lost in deterministic execution.
   - Observed symptom:
     - `Create a reminder titled "Pay rent" for today 12 pm.` returned a reminder-style answer, but the persisted item was `itemType = MEETING`.
   - Root-cause hypothesis:
     - [PlannerExecutionService.java](/C:/Users/muham/Documents/mab-embabel-local/tools-app/src/main/java/com/mab/tools/service/PlannerExecutionService.java) `createCalendarItem(...)` only respects `arguments.itemType` or `targetLookup.itemType`.
     - Common planner variants such as `targetEntityType = calendarItem`, `taskType = reminder`, or reminder semantics in the user intent are not normalized into `itemType`.

5. Contact resolution still over-matches and duplicates recipients/participants.
   - Observed symptom:
     - Creating an email for `Joe` persisted `joe@example.local, joe@example.com`.
     - Creating a meeting with `Alex` persisted both `alex@example.local` and `alex@example.com`.
   - Root-cause hypothesis:
     - [ToolRepository.java](/C:/Users/muham/Documents/mab-embabel-local/tools-app/src/main/java/com/mab/tools/repository/ToolRepository.java) `findContactsByNames(...)` matches both full name and first-name-only, which explodes when multiple contacts share a first name.
     - [PlannerExecutionService.java](/C:/Users/muham/Documents/mab-embabel-local/tools-app/src/main/java/com/mab/tools/service/PlannerExecutionService.java) `resolveRecipients(...)` and `resolveParticipants(...)` treat all matches as acceptable instead of raising ambiguity.

6. Email update/rewrite behavior is not deterministic enough.
   - Observed symptom:
     - `Update draft ID ... to make it shorter.` sometimes returned a polished shorter version in the final answer even though the persisted draft did not clearly change, and on a later run asked for extra clarification instead of applying a safe rewrite.
   - Root-cause hypothesis:
     - [PlannerAgentService.java](/C:/Users/muham/Documents/mab-embabel-local/orchestrator-app/src/main/java/com/mab/orchestrator/service/PlannerAgentService.java) `requiresRewrite(...)` only recognizes a narrow set of rewrite markers.
     - Planner outputs such as `makeItShorter`, `action=shorten`, or similar variants are not normalized before execution.
     - The final answer generator can present a rewritten draft even when the backend result was only clarification.

### Wiring Hardening

7. Planner output normalization is still too permissive and too lossy.
   - Observed symptom:
     - Live traces show `targetLookup` alternating between an object and bare strings like `"draftReference"`, `"titleLike"`, and `"itemType"`.
   - Root-cause hypothesis:
     - [PlannerAgentService.java](/C:/Users/muham/Documents/mab-embabel-local/orchestrator-app/src/main/java/com/mab/orchestrator/service/PlannerAgentService.java) validates only `taskFamily` and `action`, not planner contract shape.
     - [PlannerExecutionService.java](/C:/Users/muham/Documents/mab-embabel-local/tools-app/src/main/java/com/mab/tools/service/PlannerExecutionService.java) compensates partially but not enough for malformed planner payloads.

8. UX continuity is still built on conversational text instead of explicit pending-task state.
   - Observed symptom:
     - Continuation success depends on whether prior assistant/user messages contain the right keywords.
   - Root-cause hypothesis:
     - The frontend stores only raw assistant text plus traces.
     - No structured `pendingPlan` or `clarification context` is persisted in chat state or returned by the API.

9. Frontend truth alignment is partly present but not fully hardened.
   - Observed symptom:
     - The UI likely benefits from timeout/slow/stopped states already in [App.jsx](/C:/Users/muham/Documents/mab-embabel-local/frontend/src/App.jsx), but there is no explicit rendering contract that distinguishes `clarification`, `validation error`, and `completed mutation` from backend state.
   - Root-cause hypothesis:
     - [App.jsx](/C:/Users/muham/Documents/mab-embabel-local/frontend/src/App.jsx) renders the assistant result as a single text blob plus traces, which leaves the primary UX dependent on generated wording instead of structured mutation outcome.

### Optional Improvements

10. Model selection UX is functionally wired but could be hardened further.
   - Current status:
     - `GET /api/state` exposes approved models only.
     - `PUT /api/state/model` persists active model.
     - Orchestrator reads the active model through `ToolsClient.systemState()`.
   - Remaining risk:
     - No automated regression coverage currently proves model switching across repeated planner prompts.

## Implementation Phases

## Phase 1: Enforce a Strict Planner Contract

Files/components:

- [PlannerAgentService.java](/C:/Users/muham/Documents/mab-embabel-local/orchestrator-app/src/main/java/com/mab/orchestrator/service/PlannerAgentService.java)
- [PlannerActionPlan.java](/C:/Users/muham/Documents/mab-embabel-local/shared-models/src/main/java/com/mab/shared/model/PlannerActionPlan.java)
- [PlannerLookup.java](/C:/Users/muham/Documents/mab-embabel-local/shared-models/src/main/java/com/mab/shared/model/PlannerLookup.java)
- Planner-related shared DTOs if needed

Implementation:

- Reject planner payloads where `targetLookup` is not a JSON object.
- Add planner normalization for common variant fields:
  - email rewrite markers: `makeItShorter`, `rewrite`, `shorten`, `rewriteInstruction`
  - calendar item type markers: `taskType`, reminder/task semantics, `targetEntityType`
  - date/time fields: `dateTime`, `startTime`, `startDate`
- Expand validation so a plan is only considered valid when:
  - `taskFamily` matches
  - `action` is supported
  - lookup fields are structurally valid
  - arguments are normalized before execution
- Trace both raw planner output and normalized planner output.

Acceptance criteria:

- Bare-string `targetLookup` plans no longer reach execution unchanged.
- `Update draft ID ... to make it shorter` normalizes into a deterministic rewrite-capable email update plan.
- `Create a reminder ...` normalizes into `itemType = REMINDER`.

## Phase 2: Make Continuations Stateful Instead of Keyword-Dependent

Files/components:

- [PlannerAgentService.java](/C:/Users/muham/Documents/mab-embabel-local/orchestrator-app/src/main/java/com/mab/orchestrator/service/PlannerAgentService.java)
- [AgentQueryResponse.java](/C:/Users/muham/Documents/mab-embabel-local/shared-models/src/main/java/com/mab/shared/model/AgentQueryResponse.java)
- [ConversationTurn.java](/C:/Users/muham/Documents/mab-embabel-local/shared-models/src/main/java/com/mab/shared/model/ConversationTurn.java)
- [App.jsx](/C:/Users/muham/Documents/mab-embabel-local/frontend/src/App.jsx)

Implementation:

- Introduce a structured pending clarification payload in the agent response.
- Persist pending plan context in the frontend session instead of inferring it from natural-language assistant text.
- Fix `looksLikeNameOnlyResponse(...)` so name-only sender replies do not break when lowercased.
- Re-run family detection with pending workflow bias when clarification is active.

Acceptance criteria:

- `Draft an email to Joe ...` -> `Who should the sender be?` -> `John Doe.` completes the original email flow.
- `Change the task type from meeting to reminder.` -> `the one titled "Project Sync" for today 4 pm` updates the intended item.
- Continuation handling no longer depends on the assistant wording containing `sender`, `draft`, `calendar`, or similar keywords.

## Phase 3: Fix Deterministic Matching and Ambiguity Handling

Files/components:

- [PlannerExecutionService.java](/C:/Users/muham/Documents/mab-embabel-local/tools-app/src/main/java/com/mab/tools/service/PlannerExecutionService.java)
- [ToolRepository.java](/C:/Users/muham/Documents/mab-embabel-local/tools-app/src/main/java/com/mab/tools/repository/ToolRepository.java)

Implementation:

- Change contact resolution to return:
  - exact single match
  - ambiguity with candidate IDs/emails
  - no match
- Stop auto-merging all first-name matches into recipient lists.
- Use non-UUID `targetEntityId` as a possible title/draft reference during match resolution.
- Match calendar items with compound scoring:
  - exact ID
  - title
  - date
  - time
  - participant
  - item type
- For ambiguous results, return truthful clarification with candidate summaries.

Acceptance criteria:

- `Joe` does not automatically expand to both `joe@example.local` and `joe@example.com` unless the user explicitly confirms both.
- `Alex` participant resolution clarifies when multiple Alex contacts exist.
- Title/date/time follow-ups resolve the correct calendar item when there is one unique match.
- Invalid or ambiguous updates produce no mutation.

## Phase 4: Ground Email Generation and Final Answers to Backend Truth

Files/components:

- [PlannerAgentService.java](/C:/Users/muham/Documents/mab-embabel-local/orchestrator-app/src/main/java/com/mab/orchestrator/service/PlannerAgentService.java)
- [OllamaClient.java](/C:/Users/muham/Documents/mab-embabel-local/orchestrator-app/src/main/java/com/mab/orchestrator/client/OllamaClient.java)

Implementation:

- Replace free-form email generation prompts with a constrained prompt that explicitly forbids:
  - invented dates
  - invented times
  - invented platforms/rooms
  - placeholders
  - duplicate closings/signatures
- Add deterministic post-generation checks before persistence:
  - no placeholder markers like `[Your Name]`
  - no unsupported invented schedule details unless supplied by user or prior structured context
- Replace `finalizeAnswer(...)` free-form prompting with a structured rendering step:
  - render from persisted `EmailDraftRecord` / `CalendarItemRecord`
  - only use the model for style if the content fields are passed explicitly and validated
- Treat clarification and validation failures as first-class outputs, not model-rewritten prose.

Acceptance criteria:

- Email drafts do not invent time/platform/room details absent from prompt or validated context.
- Final answers match persisted `subject`, `recipient`, `date`, `time`, and `itemType`.
- Clarification responses never read like successful completion.

## Phase 5: Harden Frontend Truth and Trace UX

Files/components:

- [App.jsx](/C:/Users/muham/Documents/mab-embabel-local/frontend/src/App.jsx)
- [styles.css](/C:/Users/muham/Documents/mab-embabel-local/frontend/src/styles.css)
- [SystemStateResponse.java](/C:/Users/muham/Documents/mab-embabel-local/shared-models/src/main/java/com/mab/shared/model/SystemStateResponse.java)

Implementation:

- Render structured outcome banners for:
  - `COMPLETED`
  - `CLARIFICATION`
  - `VALIDATION_ERROR`
- Surface candidate disambiguation data directly in the chat and editor views.
- Show the normalized plan and execution result as primary trace stages.
- Keep current loading/slow/stopped states, but bind them to structured backend outcomes.
- Confirm the model selector reflects the same active model shown by `/api/state` after refresh and after a completed query.

Acceptance criteria:

- The chat primary answer distinguishes success vs clarification vs validation failure without relying on generated prose.
- Trace view clearly separates raw planner output, normalized plan, validation, execution, and final rendering.
- Switching to `qwen2.5:3b` and back updates the visible active model and subsequent traces consistently.

## Test Plan

## Backend Unit/Service Tests

Add or extend tests in:

- [PlannerAgentServiceTest.java](/C:/Users/muham/Documents/mab-embabel-local/orchestrator-app/src/test/java/com/mab/orchestrator/service/PlannerAgentServiceTest.java)
- [PlannerExecutionServiceTest.java](/C:/Users/muham/Documents/mab-embabel-local/tools-app/src/test/java/com/mab/tools/service/PlannerExecutionServiceTest.java)
- add new tests for final-answer rendering if needed

Required tests:

- Sender-only follow-up `John Doe.` stays in the email workflow.
- Calendar title/date/time follow-up resolves an existing item after an ambiguity turn.
- Planner normalization converts variant fields like `makeItShorter`, `taskType`, `dateTime`, and string `targetLookup`.
- Contact resolution returns ambiguity instead of recipient explosion for duplicate first names.
- Reminder/task creation persists `REMINDER` / `TASK` correctly.
- Final answer rendering for completed email/calendar actions preserves backend fields exactly.
- Clarification and validation responses are not rendered as success summaries.
- Model selection test proves `tools-app` state and orchestrator model choice stay aligned.

## Frontend Tests

Add focused tests for:

- request in-flight, slow, timeout, stopped, and completed states
- rendering of structured clarification and validation results
- persistence of pending clarification context across turns
- model selector refresh after change

## Live Verification Plan

Run after each major phase:

1. `python scripts/deploy.py plan`
2. `python scripts/deploy.py bootstrap`
3. `GET http://localhost:8082/api/state` and confirm active model
4. Verify the frontend uses the same active model value the backend reports
5. Exercise these prompts through the Agent Console or API:
   - `Draft an email to Joe about tomorrow's check-in.`
   - follow-up `John Doe.`
   - `Draft an email to Joe about tomorrow's check-in. My name is John Doe.`
   - `Update draft ID <id> to make it shorter.`
   - `Schedule a meeting with Alex tomorrow at 3pm titled "Project Sync".`
   - `Create a reminder titled "Pay rent" for today 12 pm.`
   - `Update Event ID: <id> to 4pm.`
   - `Change the task type from meeting to reminder.`
   - follow-up `the one titled "Project Sync" for today 4 pm`
6. Inspect traces for:
   - family selection
   - raw planner JSON
   - normalized planner plan
   - validation/execution result
   - final rendering
7. Verify persisted backend truth in:
   - `GET /api/email/drafts`
   - `GET /api/calendar/items`
8. Switch models:
   - `PUT /api/state/model` to `qwen2.5:3b`
   - repeat one email prompt
   - switch back to `qwen2.5:7b-instruct`

## Acceptance Criteria By Phase

### Phase 1 complete when:

- malformed planner outputs are normalized or rejected before execution
- reminder/task semantics survive planning into persistence

### Phase 2 complete when:

- short clarification replies continue the active workflow reliably
- clarification context is explicit and not inferred from prose alone

### Phase 3 complete when:

- partial-name recipient explosion is replaced by deterministic ambiguity handling
- title/date/time and ID-based updates target the correct entity or fail truthfully

### Phase 4 complete when:

- persisted drafts and events are the single source of truth for user-facing success text
- no invented time/platform/room/sender placeholders appear in generated output

### Phase 5 complete when:

- the frontend primary UX reflects backend truth and status class directly
- model selector state, traces, and visible outcomes stay consistent after switching models

## Priority Order

1. Response grounding and final-answer truthfulness
2. Stateful clarification continuity
3. Deterministic entity matching and ambiguity handling
4. Planner payload normalization
5. Frontend truth/trace hardening
6. Additional model-selection polish

## Handoff Notes

- Treat the deployment/startup issues in `external-user-ux-hiccups.md` as mostly historical unless they reappear during regression.
- Treat the current must-fix list above as the real product backlog.
- Do not hide deterministic failures behind friendlier LLM prose.
- If a defect is not reproducible after the above fixes, remove it from the must-fix list rather than carrying it forward.
