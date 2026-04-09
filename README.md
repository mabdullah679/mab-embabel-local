# domains-api

Local chat application that answers questions from a Spring Boot consumer API through a Spring orchestrator, Ollama, and a React frontend.

The current working version is intentionally narrow:
- the frontend is chat-only
- the backend reads application data only from the Spring consumer REST API
- the orchestrator uses Ollama to turn retrieved record data into final responses
- Prometheus and Grafana remain available for local observability

## Current Architecture

- `orchestrator-app/`
  - Spring Boot API exposing `POST /agent/query`
  - owns query interpretation, record selection, and answer synthesis
  - calls the Spring consumer API over REST
- `shared-models/`
  - small DTO module shared by the orchestrator request/response path
- `frontend/`
  - React chat interface
  - sends user prompts to the orchestrator only
- `docker/`
  - Prometheus and Grafana configuration
- `scripts/`
  - local deployment/bootstrap helpers

External dependency:
- Spring consumer API expected at `http://localhost:8080`
  - `GET /api/health`
  - `GET /api/records`
  - `GET /api/records/{id}`

## Runtime Flow

1. The user sends a message from the chat UI.
2. The frontend posts that message to `POST /agent/query` on the orchestrator.
3. The orchestrator decides whether the request is a health check, a direct record lookup, or a broader record search.
4. The orchestrator calls the Spring consumer API over REST to fetch record data.
5. The orchestrator passes the retrieved record payloads to Ollama for grounded answer synthesis.
6. The orchestrator returns the answer plus trace data to the frontend.

## Spring Consumer REST Integration

This is the backend path that handles REST calls to the Spring Boot consumer API.

Main entry points:
- `orchestrator-app/src/main/java/com/domain/orchestrator/controller/AgentController.java`
  - HTTP controller for `POST /agent/query`
  - hands requests to the orchestrator service
- `orchestrator-app/src/main/java/com/domain/orchestrator/service/PlannerAgentService.java`
  - main orchestration logic
  - inspects the user query
  - decides whether to call health, fetch a specific record, or search records
  - builds the grounded prompt for Ollama from returned Spring consumer data
- `orchestrator-app/src/main/java/com/domain/orchestrator/client/ToolsClient.java`
  - despite the legacy class name, this is now the REST client for the Spring consumer API
  - performs:
    - `health()` -> `GET /api/health`
    - `records()` -> `GET /api/records`
    - `record(long id)` -> `GET /api/records/{id}`
- `orchestrator-app/src/main/resources/application.yml`
  - configures the consumer base URL with `consumer.base-url`
  - default is `http://localhost:8080`

Related DTOs:
- `shared-models/src/main/java/com/domain/shared/model/ConsumerHealthResponse.java`
- `shared-models/src/main/java/com/domain/shared/model/ConsumerRecord.java`
- `shared-models/src/main/java/com/domain/shared/model/AgentQueryRequest.java`
- `shared-models/src/main/java/com/domain/shared/model/AgentQueryResponse.java`
- `shared-models/src/main/java/com/domain/shared/model/PlannerActionPlan.java`
- `shared-models/src/main/java/com/domain/shared/model/PlannerExecutionResult.java`
- `shared-models/src/main/java/com/domain/shared/model/ToolTrace.java`

For codebase analysis, the shortest useful reading path is:
1. `orchestrator-app/src/main/java/com/domain/orchestrator/controller/AgentController.java`
2. `orchestrator-app/src/main/java/com/domain/orchestrator/service/PlannerAgentService.java`
3. `orchestrator-app/src/main/java/com/domain/orchestrator/client/ToolsClient.java`
4. `orchestrator-app/src/main/java/com/domain/orchestrator/client/OllamaClient.java`
5. `shared-models/src/main/java/com/domain/shared/model/`

## Frontend

The frontend is a chat-only React application.

Relevant files:
- `frontend/src/App.jsx`
  - session management
  - chat submission
  - trace rendering
- `frontend/src/styles.css`
  - chat UI styling

The frontend talks only to:
- `http://localhost:8081/agent/query`

## Service URLs

- Frontend: `http://localhost:5173`
- Orchestrator: `http://localhost:8081`
- Spring consumer API: `http://localhost:8080`
- Ollama API: `http://localhost:11434`
- Prometheus: `http://localhost:9090`
- Grafana: `http://localhost:3000`

## Prerequisites

- Docker Desktop
- Java 21 + Maven
- Node.js 20+
- Ollama with:
  - `qwen2.5:7b-instruct`
  - `qwen2.5:3b`
- Spring consumer API running locally on port `8080`

## Start Everything

```bash
python scripts/deploy.py bootstrap
```

Or:

```bash
make bootstrap
```

The deployment launcher:
- detects whether Ollama should run in Docker or on the host
- builds the orchestrator and frontend
- starts Prometheus and Grafana
- waits for orchestrator health

Preview the detected plan:

```bash
python scripts/deploy.py plan
```

## Quick Checks

Health check against the Spring consumer API:

```bash
curl http://localhost:8080/api/health
```

Fetch one record from the Spring consumer API:

```bash
curl http://localhost:8080/api/records/4
```

Run a chat query through the orchestrator:

```bash
curl -X POST http://localhost:8081/agent/query \
  -H "Content-Type: application/json" \
  -d '{"query":"show record 4","history":[]}'
```

Run a broader data question:

```bash
curl -X POST http://localhost:8081/agent/query \
  -H "Content-Type: application/json" \
  -d '{"query":"what is synced right now?","history":[]}'
```

## Deployment Files

- `docker-compose.yml`
  - base local stack
- `docker-compose.nvidia.yml`
  - adds Ollama with NVIDIA GPU passthrough
- `docker-compose.host-ollama.yml`
  - points the orchestrator at host Ollama
- `scripts/deploy.py`
  - chooses strategy and runs bootstrap/up/down flows

## Telemetry

- Orchestrator metrics: `http://localhost:8081/actuator/prometheus`
- Prometheus scrapes the orchestrator
- Grafana is provisioned from `docker/grafana/`

## Notes

- The class name `ToolsClient` is legacy naming; it now serves as the Spring consumer REST client.
- The backend no longer depends on the retired `tools-app` module.
- The application data source is the Spring consumer API, not a local Postgres-owned tool service.
- Ollama is still used for response synthesis, but not as a data source.
