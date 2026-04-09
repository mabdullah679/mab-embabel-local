# Architecture

This document describes the current shipped architecture.

## Stack

1. React frontend
2. Spring orchestrator
3. Spring consumer API
4. Ollama
5. Prometheus + Grafana

The important boundary is:
- this repo owns the frontend and orchestrator
- the Spring consumer API is an external local dependency
- the orchestrator is the only backend component in this repo that reads application data

## Frontend

The frontend is a chat-only interface.

Responsibilities:
- capture user prompts
- keep local chat sessions in browser storage
- send requests to `POST /agent/query`
- render answers and traces

Main files:
- `frontend/src/App.jsx`
- `frontend/src/styles.css`

The frontend does not call the Spring consumer API directly.

## Orchestrator

The orchestrator is the backend entrypoint.

Responsibilities:
- expose `POST /agent/query`
- classify the incoming prompt as:
  - consumer health request
  - direct record lookup
  - general record search
- call the Spring consumer API over REST
- synthesize a final answer with Ollama
- return traces for inspection

Main files:
- `orchestrator-app/src/main/java/com/domain/orchestrator/controller/AgentController.java`
- `orchestrator-app/src/main/java/com/domain/orchestrator/service/PlannerAgentService.java`
- `orchestrator-app/src/main/java/com/domain/orchestrator/client/ToolsClient.java`
- `orchestrator-app/src/main/java/com/domain/orchestrator/client/OllamaClient.java`

## Spring Consumer API Integration

The REST integration is handled here:

- `orchestrator-app/src/main/java/com/domain/orchestrator/client/ToolsClient.java`

That class is legacy-named, but it is now the Spring consumer REST client.

It performs:
- `health()` -> `GET /api/health`
- `records()` -> `GET /api/records`
- `record(long id)` -> `GET /api/records/{id}`

The class that decides which of those methods to call is:
- `orchestrator-app/src/main/java/com/domain/orchestrator/service/PlannerAgentService.java`

The Spring consumer base URL is configured in:
- `orchestrator-app/src/main/resources/application.yml`

Property:
- `consumer.base-url`

Default:
- `http://localhost:8080`

## Ollama

Ollama is used for answer synthesis only.

It is not treated as a data source.

Responsibilities:
- receive grounded prompts from the orchestrator
- turn Spring consumer record payloads into concise answers

Main file:
- `orchestrator-app/src/main/java/com/domain/orchestrator/client/OllamaClient.java`

## Shared Contracts

The shared DTO layer is intentionally small.

Current module:
- `shared-models/`

Current DTOs:
- `AgentQueryRequest`
- `AgentQueryResponse`
- `ConsumerHealthResponse`
- `ConsumerRecord`
- `ConversationTurn`
- `PlannerActionPlan`
- `PlannerExecutionResult`
- `ToolTrace`

These live under:
- `shared-models/src/main/java/com/domain/shared/model/`

## End-To-End Request Flow

1. User submits a message in the frontend.
2. Frontend posts the message to `POST /agent/query`.
3. `AgentController` forwards the request to `PlannerAgentService`.
4. `PlannerAgentService` determines whether to:
   - check consumer health
   - fetch one specific record
   - fetch the record list and rank likely matches
5. `ToolsClient` performs the Spring consumer API REST call.
6. `PlannerAgentService` builds a grounded prompt from the returned record data.
7. `OllamaClient` sends that prompt to Ollama.
8. The orchestrator returns the answer and traces to the frontend.

## Observability

Observability is provided through:
- Spring Actuator on the orchestrator
- Prometheus
- Grafana

Current telemetry focus:
- orchestrator health
- orchestrator request metrics
- basic JVM/runtime metrics

## One-Line Summary

This is a chat-first local application where a Spring orchestrator reads data from an external Spring consumer REST API, uses Ollama to synthesize grounded answers, and returns them to a React frontend with trace visibility.
