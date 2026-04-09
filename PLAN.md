# Current Plan

This branch is in documentation-and-release-hardening mode for the current architecture.

## Current System

Shipped application shape:
- React chat UI
- Spring orchestrator
- Spring consumer API integration over REST
- Ollama answer synthesis
- Prometheus and Grafana for local observability

## Immediate Priorities

1. Keep all repository documentation aligned to the current architecture.
2. Keep the deployment path deterministic for client delivery.
3. Preserve a small and understandable backend surface area.

## Current Backend Surface

Main backend files:
- `orchestrator-app/src/main/java/com/domain/orchestrator/controller/AgentController.java`
- `orchestrator-app/src/main/java/com/domain/orchestrator/service/PlannerAgentService.java`
- `orchestrator-app/src/main/java/com/domain/orchestrator/client/ToolsClient.java`
- `orchestrator-app/src/main/java/com/domain/orchestrator/client/OllamaClient.java`
- `orchestrator-app/src/main/resources/application.yml`

Shared contracts:
- `shared-models/src/main/java/com/domain/shared/model/`

## Delivery Checklist

- README matches current runtime behavior
- deployment guide matches current compose/bootstrap behavior
- architecture guide matches the current code layout
- legacy references to removed backend modules are eliminated from primary docs
- orchestrator still packages cleanly with:

```bash
mvn -q -pl orchestrator-app -am package -DskipTests
```

## Validation

Recommended smoke checks before shipping:

1. `python scripts/deploy.py bootstrap`
2. `python scripts/deploy.py ps`
3. `curl http://localhost:8080/api/health`
4. `curl http://localhost:8080/api/records/4`
5. `curl -X POST http://localhost:8081/agent/query -H "Content-Type: application/json" -d '{"query":"show record 4","history":[]}'`
6. open `http://localhost:5173` and verify the chat flow manually

## Notes

- The name `ToolsClient` is still acceptable for now, but it is semantically a Spring consumer REST client.
- If the client wants internet-facing or production deployment later, a dedicated deployment plan should be written rather than extending the local-only assumptions in the current docs.
