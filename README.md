# mab-embabel-local

A local chat app: a React chat UI sends questions to a Spring Boot orchestrator, which fetches records from an external Spring consumer REST API and has a local Ollama model write a grounded answer.

Design docs, not a formal spec: [ARCHITECTURE.md](ARCHITECTURE.md), [DEPLOY.md](DEPLOY.md), [PLAN.md](PLAN.md), [ANALYZE.md](ANALYZE.md).

```text
browser :5173 -> frontend (nginx) -> orchestrator-app :8081 -> consumer API :8080   (external)
                                              |-----------------> Ollama :11434     (external or container)
                    Prometheus :9090 --scrapes--> /actuator/prometheus ; Grafana :3000
```

Not local or contained: the Spring consumer API on port 8080 (not in this repo; the sibling `prod-stack-sim-local` repo's `consumer-app` is a candidate, unverified here) and the Ollama models `qwen2.5:7b-instruct` and `qwen2.5:3b`. Despite the repo name, no code here uses the Embabel framework (no reference to it in any tracked file).

## Prerequisites
- Docker with Compose v2, Python 3 (for the launcher), free ports 3000, 5173, 8081, 9090.
- Ollama with the two models above, reachable from the orchestrator (host Ollama, or a container via the NVIDIA overlay).
- A consumer API answering `GET /api/health`, `/api/records`, `/api/records/{id}` on port 8080.
- Not needed on the host to run: Java, Maven, Node (images build them). For local builds: JDK 17+ and Maven, Node 20+.

## Run it
1. Start the consumer API and Ollama (pull both models).
2. `python scripts/deploy.py plan` previews the strategy it detects.
3. `python scripts/deploy.py bootstrap` (or `make bootstrap`) builds and starts everything.
4. Open http://localhost:5173 and ask "show record 4".

Failure branch: the orchestrator refuses to start if Ollama is unreachable or a configured model is missing ([OllamaModelValidator.java](orchestrator-app/src/main/java/com/domain/orchestrator/config/OllamaModelValidator.java)); see [DEPLOY.md](DEPLOY.md) for host-Ollama vs NVIDIA strategies and [external-user-ux-hiccups.md](external-user-ux-hiccups.md). Override URLs with `SPRING_CONSUMER_BASE_URL`, `OLLAMA_BASE_URL` ([docker-compose.yml](docker-compose.yml)). `make wait` runs a PowerShell script and works on Windows only.

## What it does
| Action | Result |
|---|---|
| Chat: "show record 4" | Orchestrator fetches `GET /api/records/4` and Ollama summarises it, with a trace of the calls |
| Chat: "what is synced right now?" | Fetches all records, answers from them |
| Health-style question | Calls consumer `/api/health` |
| Open Grafana (admin/admin) | Provisioned dashboard from [docker/grafana](docker/grafana) |

Routing between those three paths is regex based ([PlannerAgentService.java](orchestrator-app/src/main/java/com/domain/orchestrator/service/PlannerAgentService.java)); the model only writes the final answer.

## Interface
| Endpoint | Method | Auth |
|---|---|---|
| `:8081/agent/query` body `{"query":"...","history":[]}` | POST | none |
| `:8081/actuator/health`, `/actuator/prometheus` | GET | none |
| `:8080/api/health`, `/api/records`, `/api/records/{id}` | GET (consumer, external) | n/a |

## Tests
No automated tests exist in this repo (no `src/test`, no frontend test runner). CI only builds: `mvn package` for the Java modules, `npm ci && npm run build` for the frontend, `docker compose build`, plus a gitleaks scan. The end-to-end chat path is not exercised by anything automated, because it needs Ollama and the consumer API.

## Layout
| Path | Role |
|---|---|
| [orchestrator-app](orchestrator-app) | Spring Boot API; [AgentController](orchestrator-app/src/main/java/com/domain/orchestrator/controller/AgentController.java), [PlannerAgentService](orchestrator-app/src/main/java/com/domain/orchestrator/service/PlannerAgentService.java), [ToolsClient](orchestrator-app/src/main/java/com/domain/orchestrator/client/ToolsClient.java) (REST client for the consumer API; legacy name), [OllamaClient](orchestrator-app/src/main/java/com/domain/orchestrator/client/OllamaClient.java) |
| [shared-models](shared-models) | Request/response DTOs |
| [frontend](frontend) | React + Vite chat UI ([App.jsx](frontend/src/App.jsx)) served by nginx |
| [docker](docker) | Prometheus, Grafana config; [postgres-init](docker/postgres-init) is unused (see decisions) |
| [scripts/deploy.py](scripts/deploy.py) | Picks the Ollama strategy and runs bootstrap/up/down |
| [docker-compose.yml](docker-compose.yml), [nvidia](docker-compose.nvidia.yml), [host-ollama](docker-compose.host-ollama.yml) | Base stack and overlays |
| [LLM_RELEASE_PACKAGING.txt](LLM_RELEASE_PACKAGING.txt) | How Ollama models were moved to an offline machine |
| [CLAUDE.md](CLAUDE.md) | Notes for AI sessions |

## Design decisions
- Records come only from the consumer REST API, not a local database: a local Postgres would duplicate the source of truth. [docker/postgres-init](docker/postgres-init) is left from the retired design and no compose file mounts it.
- Ollama is used for wording, not for deciding what to fetch: the regex router is deterministic and testable; an LLM planner would add a failure mode.
- Prometheus and Grafana images are `latest` and Grafana uses the default admin/admin login: acceptable for a local stack, not for exposure.
