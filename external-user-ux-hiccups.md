# External User UX Notes

This file now tracks only issues relevant to the current shipped architecture.

## Current Product Context

Current stack:
- frontend
- orchestrator-app
- ollama
- prometheus
- grafana
- external Spring consumer API on `localhost:8080`

## Known Delivery Considerations

### 1. The Spring consumer API must already be running

User-visible impact:
- the frontend and orchestrator can start successfully while the app is still functionally unusable
- user questions will fail if `http://localhost:8080` is unavailable

What to verify:
- `curl http://localhost:8080/api/health`

### 2. `ToolsClient` is legacy-named

User-visible impact:
- none directly

Engineering impact:
- a senior engineer reading the code can initially assume it still talks to an internal tools service
- current docs now clarify that it is the Spring consumer REST client

### 3. Ollama warmup still matters on slower hardware

User-visible impact:
- first-answer latency can be noticeably slower than later requests

What to verify:
- bootstrap completes model warmup
- first interactive prompt returns in acceptable time on the target client machine

## Current Recommendation

Before any client handoff or demo:

1. Start the Spring consumer API first.
2. Run `python scripts/deploy.py bootstrap`.
3. Verify `http://localhost:8080/api/health`.
4. Verify `http://localhost:8081/actuator/health/readiness`.
5. Run one direct-record query and one broad query through the UI.

This file intentionally no longer preserves the old `tools-app` startup and planner-history issues because those belonged to a retired architecture.
