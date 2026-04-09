# Analysis Notes

This file records the current analysis posture for the repository.

## Current Product Shape

The active product is:
- a chat-only frontend
- a Spring orchestrator backend
- a REST integration to the Spring consumer API
- Ollama-backed answer synthesis

The repo no longer ships:
- the old `tools-app` backend
- local calendar/email/contact management features
- local RAG ingestion/retrieval flows

## Backend Reading Order

For a fast technical review, read the code in this order:

1. `README.md`
2. `ARCHITECTURE.md`
3. `orchestrator-app/src/main/java/com/domain/orchestrator/controller/AgentController.java`
4. `orchestrator-app/src/main/java/com/domain/orchestrator/service/PlannerAgentService.java`
5. `orchestrator-app/src/main/java/com/domain/orchestrator/client/ToolsClient.java`
6. `orchestrator-app/src/main/java/com/domain/orchestrator/client/OllamaClient.java`
7. `orchestrator-app/src/main/resources/application.yml`
8. `shared-models/src/main/java/com/domain/shared/model/`

## Core Observations

- `ToolsClient` is a legacy class name; it now owns the Spring consumer REST calls.
- `PlannerAgentService` is the backend decision point for record lookup and answer synthesis.
- The frontend speaks only to the orchestrator, not to the Spring consumer API directly.
- The Spring consumer API is an external local dependency and must be running separately.

## Current Risks

- Documentation drift can recur if older references to `tools-app` remain in non-core notes or diagrams.
- The `ToolsClient` name can mislead new contributors unless they read `README.md` or `ARCHITECTURE.md`.
- Record ranking in `PlannerAgentService` is intentionally lightweight; future feature work should treat it as the first review point for retrieval quality.

## Status

This file is intentionally brief and current-state only.

Historical handoff notes tied to the retired `tools-app` architecture have been removed so the ship branch reflects the product that now exists.
