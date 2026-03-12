# Implementation Plan: LLM Planner + Deterministic Tools

## Status

This plan is intended to be implementation-ready for the next agent.

When this plan is complete, the app should follow this model:

- LLM handles user-facing language, interpretation, clarification, and structured action planning
- backend tools handle validation, CRUD, persistence, retrieval, and trace logging
- model output is never treated as authoritative state

## Primary Objective

Refactor the app so that:

- email drafting is LLM-generated, not heuristic-template-driven
- calendar and email CRUD can be driven by LLM-produced structured actions
- the orchestrator becomes the LLM-first planning layer
- the tools service remains the deterministic execution layer

## Non-Goals

Do not do these in this phase:

- multi-step autonomous agent loops
- direct LLM writes to the database
- replacing deterministic retrieval logic for RAG
- external provider integrations like Google Calendar or SMTP
- removing all heuristics before the new path is stable

## Required End State

By the end of this work:

- user prompts can be interpreted conversationally
- the orchestrator uses the LLM to produce structured action plans
- tools execute only validated structured requests
- email drafts sound naturally written
- calendar/email update and delete flows support conversational phrasing
- traces show what the model planned and what the backend executed

## System Responsibilities

## LLM Responsibilities

The LLM should do:

- conversational interpretation
- clarification generation
- structured action planning in strict JSON
- email subject/body generation
- final user-facing response generation
- semantic ranking where appropriate

The LLM should not do:

- direct persistence
- direct CRUD execution
- source-of-truth state mutation

## Backend Responsibilities

The backend should do:

- action validation
- entity lookup and disambiguation support
- CRUD execution
- persistence
- retrieval
- status transitions
- trace logging
- fallback handling

## Architecture Target

The target runtime flow is:

1. user sends a request
2. orchestrator assigns a broad task family
3. orchestrator prompts the LLM for a strict JSON plan
4. orchestrator validates the JSON shape
5. orchestrator maps the plan to one or more deterministic tool calls
6. tools validate business constraints and execute
7. orchestrator uses the LLM to produce the final user-facing answer
8. traces capture both model planning and tool execution

## Broad Task Families

Broad family detection should remain deterministic and lightweight.

Supported families:

- email
- calendar
- metadata
- hardware
- rag

This phase does not require replacing broad family detection with full LLM classification.

## Deliverables

The next agent should deliver the following:

### 1. LLM Planning Contract

Introduce a structured planner response contract for the orchestrator.

Minimum fields:

- `taskFamily`
- `action`
- `targetEntityType`
- `targetEntityId`
- `targetLookup`
- `arguments`
- `needsClarification`
- `clarificationQuestion`
- `confidence`

`targetLookup` should support human references like:

- recipient name
- event title
- date/time hints
- current-chat draft reference

`arguments` should hold action-specific fields.

The planner output must be strict JSON.

### 2. Email Draft Generation Rework

Replace heuristic email drafting with LLM-generated content.

New behavior:

- LLM generates:
  - subject
  - body
  - tone
  - optional rewrite notes
- backend resolves recipients
- backend persists the draft
- backend returns structured draft data

Deprecate:

- heuristic subject generation
- literal email body extraction as the main content generator
- template-first body composition as the primary drafting path

Keep:

- recipient/contact resolution
- draft persistence
- send/schedule status transitions

### 3. Email Draft CRUD via LLM Plans

Support conversational email actions:

- create draft
- update draft
- schedule draft
- send draft
- delete draft

Examples:

- "make that email shorter"
- "change the tone to formal"
- "send Joe's email tomorrow at 9"
- "delete the coffee chat draft"

Required backend behavior:

- resolve candidate drafts
- reject ambiguous plans with a structured ambiguity result
- apply deterministic updates only after validation

### 4. Calendar CRUD via LLM Plans

Support conversational calendar actions:

- create item
- update item
- reschedule item
- delete item

Examples:

- "move my meeting with Joe to 2pm"
- "cancel the reminder for today"
- "rename that task to push to staging"

Required backend behavior:

- find target calendar items using lookup hints
- validate dates/times and item existence
- apply deterministic changes

### 5. Trace Expansion

Extend trace visibility so the frontend can inspect:

- planner family selection
- planner JSON action plan
- clarification result if any
- validation result
- tool call input/output
- final answer generation step

Do not remove the existing tool traces.
Add the planning layer around them.

## Concrete Domain Model

## Email Actions

Supported planner actions:

- `create_email_draft`
- `update_email_draft`
- `schedule_email_draft`
- `send_email_draft`
- `delete_email_draft`

Suggested arguments:

- `recipientNames`
- `recipientEmails`
- `subject`
- `body`
- `tone`
- `scheduledFor`
- `rewriteInstruction`

## Calendar Actions

Supported planner actions:

- `create_calendar_item`
- `update_calendar_item`
- `delete_calendar_item`

Suggested arguments:

- `itemType`
- `title`
- `date`
- `time`
- `participants`
- `notes`

Suggested lookup hints:

- `titleLike`
- `participantName`
- `date`
- `time`
- `itemType`

## Lookup Actions

Metadata and hardware remain mostly deterministic.

The LLM should only:

- decide the family/action
- shape the request
- phrase the final answer

Do not over-LLM these flows.

## RAG Actions

RAG already follows the intended split closely.

Keep the current model:

- backend handles ingest, retrieval, fusion, context assembly
- LLM handles reranking and final answer generation

No major architectural change is required here in this phase.

## File/Area Targets

The next agent should expect to touch at least:

- `orchestrator-app/src/main/java/com/mab/orchestrator/service/PlannerAgentService.java`
- `orchestrator-app/src/main/java/com/mab/orchestrator/client/OllamaClient.java`
- `orchestrator-app/src/main/java/com/mab/orchestrator/client/ToolsClient.java`
- `shared-models/src/main/java/com/mab/shared/model/...`
- `tools-app/src/main/java/com/mab/tools/service/ToolsService.java`
- `tools-app/src/main/java/com/mab/tools/repository/ToolRepository.java`
- `frontend/src/App.jsx`

Potentially add new planner DTOs and trace DTOs in `shared-models`.

## Implementation Phases

## Phase 1: Planning Contract

Implement first:

- planner JSON response contract
- orchestrator prompt for strict JSON planning
- JSON parsing and shape validation
- fallback if planner JSON is invalid

Acceptance criteria:

- orchestrator can produce a valid structured plan for email create requests
- invalid JSON falls back cleanly with a traceable error

## Phase 2: Email Drafting Rework

Implement next:

- LLM-generated email subject/body/tone
- backend persistence of generated draft
- removal of heuristic-first drafting path

Acceptance criteria:

- prompts like "write an email to Joe notifying him we need to meet at 1pm today" produce natural email text
- recipient is still resolved deterministically
- traces show generated draft inputs clearly

## Phase 3: Email CRUD Plans

Implement:

- update/schedule/send/delete planner actions
- draft lookup support
- ambiguity handling

Acceptance criteria:

- conversational update prompts mutate the correct draft
- ambiguous matches return a clarification response instead of a bad write

## Phase 4: Calendar CRUD Plans

Implement:

- create/update/delete planner actions for calendar items
- lookup hints for matching existing items
- validation and ambiguity handling

Acceptance criteria:

- prompts like "move Joe's meeting to 2pm" work when there is one match
- ambiguous matches request clarification
- invalid dates/times are rejected deterministically

## Phase 5: Trace and UI Transparency

Implement:

- planner-level trace payloads
- frontend display for planner plan + validation + tool execution

Acceptance criteria:

- UI shows both the model's structured plan and the tool result
- failures are attributable to planning vs validation vs execution

## Validation Rules

Backend validation must reject:

- missing required fields
- invalid timestamps
- invalid status transitions
- updates/deletes for non-existent entities
- ambiguous entity matches without explicit disambiguation

The LLM may suggest an action, but the backend must enforce correctness.

## Clarification Rules

The system should ask for clarification when:

- multiple drafts match the target
- multiple calendar items match the target
- required scheduling information is missing
- recipient resolution is unclear

Clarification should be phrased by the LLM, but triggered by deterministic validation or lookup ambiguity.

## Fallback Rules

Fallback behavior must be explicit:

- invalid planner JSON -> fallback to safe failure with trace
- missing tool target -> clarification or safe failure
- backend validation failure -> no mutation, return structured error
- LLM generation failure for email content -> optional deterministic fallback only during transition period

The fallback path must be traceable and visible in the UI.

## Deprecations

Deprecate after replacement is stable:

### Email

- `inferEmailSubject(...)`
- heuristic-only body extraction as the primary draft generator
- rigid template-first phrasing logic

### Planner

- narrow regex-only extraction for email CRUD intent
- narrow regex-only extraction for calendar update/delete phrasing

Keep deterministic family routing unless it becomes a blocker.

## Testing Requirements

Minimum tests required:

### Planner Tests

- valid planner JSON is parsed and used
- invalid planner JSON fails safely
- clarification paths are triggered for ambiguous targets

### Email Tests

- create draft via LLM-generated content
- update existing draft via structured plan
- schedule/send/delete flows work with structured plans

### Calendar Tests

- create item via structured plan
- update existing item via structured plan
- delete existing item via structured plan
- ambiguous lookup requests clarification

### Trace Tests

- planner trace is present
- tool trace remains present
- failure path is visible

## Completion Criteria

This plan is complete when all are true:

- email drafting is LLM-generated
- email CRUD works via structured planner actions
- calendar CRUD works via structured planner actions
- backend still owns validation and persistence
- traces show planner decisions and tool execution
- heuristic drafting logic is no longer the primary path

## Handoff Note

The next agent should treat this plan as the implementation source of truth.

Priority order:

1. planner JSON contract
2. email drafting rework
3. email CRUD plans
4. calendar CRUD plans
5. trace/UI expansion

If tradeoffs are required, preserve:

- deterministic backend validation
- transparent traces
- existing RAG behavior
