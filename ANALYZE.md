# Analysis Handoff

This document is for the next coding agent working in this repo.

Your job is not to start coding immediately. Your first job is to thoroughly analyze the current codebase, the current documented behavior, and the latest observed product failures, then produce or overwrite `PLAN.md` with an implementation-ready plan to fix the remaining problems and harden the UX wiring.

The stack has already gone through major architecture and deployment work. Do not assume the remaining problems are infra-only. Most of the open issues are now in planner quality, business-rule execution, response grounding, and UX continuity.

## Primary Goal

Produce a fresh `PLAN.md` that:

- identifies the current real defects still visible in the product
- separates routing/planning issues from deterministic execution issues
- separates backend-truth problems from frontend-rendering problems
- proposes fixes in a pragmatic execution order
- includes validation steps and regression tests
- explicitly hardens the end-to-end UX so the agent feels reliable rather than brittle

## Required Inputs

Before writing `PLAN.md`, you must inspect these files:

- [README.md](/C:/Users/muham/Documents/mab-embabel-local/README.md)
- [DEPLOY.md](/C:/Users/muham/Documents/mab-embabel-local/DEPLOY.md)
- [PLAN.md](/C:/Users/muham/Documents/mab-embabel-local/PLAN.md)
- [external-user-ux-hiccups.md](/C:/Users/muham/Documents/mab-embabel-local/external-user-ux-hiccups.md)
- [scripts/deploy.py](/C:/Users/muham/Documents/mab-embabel-local/scripts/deploy.py)

Then inspect the current implementation areas:

- [PlannerAgentService.java](/C:/Users/muham/Documents/mab-embabel-local/orchestrator-app/src/main/java/com/mab/orchestrator/service/PlannerAgentService.java)
- [OllamaClient.java](/C:/Users/muham/Documents/mab-embabel-local/orchestrator-app/src/main/java/com/mab/orchestrator/client/OllamaClient.java)
- [ToolsClient.java](/C:/Users/muham/Documents/mab-embabel-local/orchestrator-app/src/main/java/com/mab/orchestrator/client/ToolsClient.java)
- [ToolsService.java](/C:/Users/muham/Documents/mab-embabel-local/tools-app/src/main/java/com/mab/tools/service/ToolsService.java)
- [PlannerExecutionService.java](/C:/Users/muham/Documents/mab-embabel-local/tools-app/src/main/java/com/mab/tools/service/PlannerExecutionService.java)
- [ToolRepository.java](/C:/Users/muham/Documents/mab-embabel-local/tools-app/src/main/java/com/mab/tools/repository/ToolRepository.java)
- [ToolController.java](/C:/Users/muham/Documents/mab-embabel-local/tools-app/src/main/java/com/mab/tools/controller/ToolController.java)
- [App.jsx](/C:/Users/muham/Documents/mab-embabel-local/frontend/src/App.jsx)
- [styles.css](/C:/Users/muham/Documents/mab-embabel-local/frontend/src/styles.css)
- [SystemStateResponse.java](/C:/Users/muham/Documents/mab-embabel-local/shared-models/src/main/java/com/mab/shared/model/SystemStateResponse.java)

Also inspect the current tests:

- [PlannerAgentServiceTest.java](/C:/Users/muham/Documents/mab-embabel-local/orchestrator-app/src/test/java/com/mab/orchestrator/service/PlannerAgentServiceTest.java)
- [PlannerExecutionServiceTest.java](/C:/Users/muham/Documents/mab-embabel-local/tools-app/src/test/java/com/mab/tools/service/PlannerExecutionServiceTest.java)
- [ToolsServiceTest.java](/C:/Users/muham/Documents/mab-embabel-local/tools-app/src/test/java/com/mab/tools/service/ToolsServiceTest.java)

## What To Analyze

You must analyze the system as a product, not just as code.

### 1. Planner Family and Continuation Quality

Determine whether the planner:

- routes email requests to email reliably
- routes calendar requests to calendar reliably
- keeps short clarification replies in the active workflow
- handles UUID / Event ID / Draft ID requests without misrouting to metadata
- asks clarification only when truly needed

Pay special attention to:

- follow-up replies like `John Doe.`
- follow-up replies like `the one titled "..." for today 12 pm`
- short continuation messages
- prompts that mention both email and schedule details

### 2. Deterministic Execution Quality

Determine whether the tools layer:

- resolves contacts correctly and without over-matching
- creates actual calendar items and updates them correctly
- updates the correct email draft/calendar item when the user references IDs or titles
- preserves sender identity correctly
- rejects invalid updates cleanly and truthfully

Pay special attention to:

- recipient explosion from partial name matching
- item type changes like `meeting -> reminder`
- planner payload normalization
- fallback execution behavior

### 3. Response Grounding

Determine whether generated answers:

- stay faithful to the backend result
- avoid inventing meetings, times, rooms, platforms, or extra context
- avoid contradicting the database state
- avoid leaking unrelated past context into current answers

Pay special attention to:

- `FinalAnswerGeneration`
- `EmailGeneration`
- any place where successful or failed tool output is turned into a user-facing answer

### 4. UX Continuity

Determine whether the user experience feels like one continuous task flow rather than disconnected single prompts.

Inspect:

- context selection behavior
- short-turn continuation handling
- visible loading / stop / failure states
- trace clarity
- whether the UI reflects actual backend truth

### 5. Model Selection Wiring

A runtime model selector now exists.

Analyze whether:

- only deploy-approved models are selectable
- the selected model is shared across tools/orchestrator behavior
- the UI reflects the real active model
- switching models is robust and does not create inconsistent state

Do not assume the selector is complete just because it exists.

## Latest Known Product Issues To Re-Evaluate

At minimum, verify whether the following are still broken, partially fixed, or fully fixed:

- email generation invents facts not present in the prompt
- final-answer generation overstates or fabricates successful outcomes
- event/draft ID updates route to the wrong family or tool
- calendar item updates by title/date are brittle
- contact matching still over- or under-matches recipients
- sender extraction still degrades into placeholders or generic text
- follow-up clarification replies still fall out of scope
- email drafts still show redundant closings or placeholder sender text
- current model selection UX is present but may not yet be fully hardened

## Required Validation Workflow

You must run the stack and test live behavior before finalizing `PLAN.md`.

At minimum:

1. run the deployment strategy preview
2. bootstrap the stack
3. verify the active model in the UI
4. run live prompts through the Agent Console
5. inspect traces
6. verify persisted state in the backend-backed views

Use prompts in these categories:

- plain email draft creation
- email draft creation with explicit sender
- email draft update by draft ID
- calendar creation by natural phrasing
- reminder/task creation with quoted titles
- calendar update by event ID
- calendar clarification follow-ups by title/date/time
- model switch followed by the same prompt on another model

## What `PLAN.md` Must Contain

Your rewritten `PLAN.md` must include:

- current system assessment
- defect list ordered by severity
- root-cause hypotheses tied to specific files/components
- concrete implementation phases
- test plan
- live verification plan
- explicit acceptance criteria for each phase

It must also distinguish between:

- must-fix product issues
- wiring hardening
- optional improvements

## Planning Standard

Do not write a vague brainstorming plan.

`PLAN.md` must be:

- implementation-ready
- ordered
- tied to files/components
- tied to observable symptoms
- realistic about dependencies and risk

If something looks flaky but you did not reproduce it, say that clearly.
If something is fixed already, say that clearly and do not keep it in the must-fix list.

## Deliverable

Your deliverable for this analysis phase is:

- an updated [PLAN.md](/C:/Users/muham/Documents/mab-embabel-local/PLAN.md)

Optional but useful if warranted:

- short additions to [external-user-ux-hiccups.md](/C:/Users/muham/Documents/mab-embabel-local/external-user-ux-hiccups.md) if you discover new externally visible failures worth preserving as evidence

## Guardrails

- Do not treat outdated documentation as truth over current code and current runtime behavior.
- Do not assume the LLM is the only issue; many remaining problems are contract-shape and UX continuity problems.
- Do not remove deterministic validation in the name of making the app seem smarter.
- Do not hide backend failure with a nicer-sounding answer.
- Prefer direct, testable fixes over more model complexity unless the evidence supports it.
