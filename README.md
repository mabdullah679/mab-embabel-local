# MAB Embabel Local

Local-first agent stack with a Spring orchestrator, a Spring MCP tools service, Ollama, Postgres + pgvector, and a React console.

For local deployment instructions, start with [DEPLOY.md](/C:/Users/muham/Documents/mab-embabel-local/DEPLOY.md). It documents the dynamic deployment launcher, platform-specific Ollama strategies, verification steps, and expected outputs.

## Current System

- `orchestrator-app`
  - `POST /agent/query`
  - intent routing and guardrails
  - MCP client for tool execution
  - final answer synthesis with `qwen2.5:7b-instruct`
- `tools-app`
  - Spring MCP server over SSE
  - browser-facing REST APIs
  - DB-backed calendar, contacts, metadata, hardware, and RAG ingest
  - hybrid RAG retrieval with dense + lexical search, fusion, reranking, and explicit context assembly
- `shared-models`
  - shared request/response DTOs across services
- `frontend`
  - threaded local chat sessions
  - trace rendering
  - RAG inspector embedded in the existing trace UI
- observability
  - actuator + prometheus endpoints
  - Prometheus + Grafana in compose

## Main Features

- Email drafting with persisted drafts
- Calendar item creation and management
- Contact management
- Metadata lookup by UUID
- Hardware record search
- Local RAG over ingested documents

## RAG Workflow

The current RAG path is:

1. ingest source text through `POST /api/rag/ingest`
2. normalize and chunk text with overlap
3. embed each chunk with `nomic-embed-text`
4. store source documents and chunk rows in Postgres
5. retrieve candidates with:
   - pgvector dense search
   - PostgreSQL full-text lexical search
   - reciprocal-rank fusion
6. rerank the shortlist with `qwen2.5:7b-instruct`
7. assemble a bounded final context
8. answer from explicit context in the orchestrator
9. expose staged retrieval artifacts in traces and the frontend inspector

## Browser APIs

- `GET /api/calendar/items`
- `GET /api/email/drafts`
- `PUT /api/email/drafts/{id}`
- `POST /api/email/drafts/{id}/schedule`
- `POST /api/email/drafts/{id}/send`
- `DELETE /api/email/drafts/{id}`
- `GET /api/contacts`
- `POST /api/contacts`
- `DELETE /api/contacts/{id}`
- `POST /api/rag/ingest`
- `GET /api/state`

## MCP Tools

- `draft_email`
- `create_calendar_item`
- `lookup_metadata`
- `search_hardware`
- `retrieve_rag_documents`

## Repo Structure

- `orchestrator-app/`
- `tools-app/`
- `shared-models/`
- `frontend/`
- `docker/`
- `docs/`

## Prerequisites

- Docker Desktop
- Java 21 + Maven
- Node.js 20+ for frontend local builds
- Ollama models:
  - `qwen2.5:7b-instruct`
  - `nomic-embed-text`

## Start Everything

```bash
python scripts/deploy.py bootstrap
```

Or:

```bash
make bootstrap
```

The deployment launcher detects the host environment and picks the appropriate tactic:

- Windows/Linux with NVIDIA available:
  - runs Ollama in Docker with GPU passthrough
- Apple Silicon macOS:
  - expects Ollama running natively on the host and points containers to `host.docker.internal:11434`
- other hosts:
  - falls back to host Ollama if available

`make bootstrap` builds the services, waits for health, pulls Ollama models using the selected strategy, and seeds RAG documents.

Preview the detected plan:

```bash
python scripts/deploy.py plan
```

## Service URLs

- Orchestrator: `http://localhost:8081`
- Tools: `http://localhost:8082`
- Frontend: `http://localhost:5173`
- Prometheus: `http://localhost:9090`
- Grafana: `http://localhost:3000`
- Ollama API: `http://localhost:11434`

## Deployment Files

- `docker-compose.yml`
  - base stack shared across platforms
- `docker-compose.nvidia.yml`
  - adds containerized Ollama with NVIDIA GPU passthrough
- `docker-compose.host-ollama.yml`
  - points app services at a host-native Ollama instance
- `scripts/deploy.py`
  - detects the host strategy and runs the correct compose combination

## Quick Checks

Ingest a document:

```bash
curl -X POST http://localhost:8082/api/rag/ingest \
  -H "Content-Type: application/json" \
  -d '{"content":"Spring MCP tools are orchestrated by a planner agent. Hybrid retrieval combines dense and lexical search."}'
```

Run a planner request:

```bash
curl -X POST http://localhost:8081/agent/query \
  -H "Content-Type: application/json" \
  -d '{"query":"schedule a meeting tomorrow at 3pm"}'
```

Run a RAG request:

```bash
curl -X POST http://localhost:8081/agent/query \
  -H "Content-Type: application/json" \
  -d '{"query":"what do the docs say about planner agents?"}'
```

## Documentation

- [DEPLOY.md](./DEPLOY.md)
- [ARCHITECTURE.md](./ARCHITECTURE.md)
- [docs/end-to-end-flow.drawio](./docs/end-to-end-flow.drawio)
- [docs/rest-vs-mcp-flow.drawio](./docs/rest-vs-mcp-flow.drawio)

## Telemetry

- Orchestrator metrics: `http://localhost:8081/actuator/prometheus`
- Tools metrics: `http://localhost:8082/actuator/prometheus`
- Prometheus scrapes both services every 10s
- Grafana is provisioned from `docker/grafana/`

## Notes

- The orchestrator still executes one selected tool per request.
- RAG is local document retrieval, not internet search.
- Email sending remains a local state transition, not SMTP delivery.
- Calendar data remains local app data, not a Google or Outlook integration.
