# External User UX Hiccups

This file captures the externally visible problems encountered while deploying and using the app locally on March 12, 2026, from an Intel macOS host.

## Environment

- Host: Intel macOS
- Deployment strategy selected by repo: host-native Ollama, not containerized Ollama
- Stack: frontend, orchestrator-app, tools-app, postgres, prometheus, grafana

## 1. Local deployment path required host Ollama and failed until it was manually installed

### User-visible symptom

- The documented bootstrap path could not complete on first run.

### What happened

- `scripts/deploy.py` selected the host-Ollama strategy.
- `ollama` was not installed on the host, so deployment could not proceed.

### External impact

- A user following the docs without Ollama already installed would hit a hard stop before the app comes up.

### Likely fix area

- Improve prerequisite detection and error messaging in `scripts/deploy.py`.
- Consider documenting host-Ollama more explicitly for macOS/Intel.

## 2. Orchestrator startup raced the tools service and crashed on first boot

### User-visible symptom

- The stack did not fully come up on first bootstrap.
- Orchestrator exited during startup and had to be restarted after tools-app was ready.

### What happened

- `docker-compose.yml` originally allowed `orchestrator-app` to start when `tools-app` was merely `service_started`, not actually healthy.
- The orchestrator initializes its MCP client at startup and timed out if tools-app was not fully ready yet.

### External impact

- First-run deployment was flaky.
- A user could see partial startup and a dead backend even though Docker showed most services being created.

### Likely fix area

- Compose dependency graph and healthchecks.
- Orchestrator startup behavior around MCP initialization.

## 3. Compose health dependency was invalid because app containers had no healthchecks

### User-visible symptom

- Tightening dependency ordering exposed another startup failure:
- Compose refused to honor `service_healthy` because `tools-app` had no healthcheck configured.

### What happened

- The app containers exposed actuator health endpoints, but compose healthchecks were not defined.

### External impact

- Deployment resilience depended on manual recovery instead of deterministic orchestration.

### Likely fix area

- Add proper compose healthchecks for `tools-app` and `orchestrator-app`.

## 4. Ollama inference timed out on this host during planner execution

### User-visible symptom

- Planner requests initially failed with messages equivalent to:
- `I couldn't build a valid action plan for that request.`

### What happened

- Both apps used a `RestTemplate` read timeout of 10 seconds.
- On this CPU-only host, the first local generation call to `qwen2.5:7b-instruct` exceeded that timeout.

### External impact

- The app looked unreliable or broken even when all services were technically up.
- First query behavior was especially poor because cold inference was slower.

### Likely fix area

- Increase configurable HTTP timeouts for Ollama-backed calls.
- Consider warmup behavior or smaller/faster default models on CPU-only hosts.

## 5. Email requests mentioning meetings were misclassified as unsupported

### User-visible symptom

- Example prompt:
- `Draft an email to Joe notifying him we have a meeting at 2pm with Alex.`
- App response:
- Request reported as outside planner scope.

### What happened

- The family detector in `orchestrator-app` requires exactly one matching family.
- The prompt above matched both:
- email keywords: `draft`, `email`
- calendar keywords: `meeting`
- Because more than one family matched, the request was labeled `unsupported` before planning.

### External impact

- Normal user prompts that combine email intent with schedule details fail.
- This is a high-visibility UX issue because it affects obvious real-world phrasing.

### Likely fix area

- `PlannerAgentService.detectTaskFamily`
- Replace exact-one-family matching with precedence rules, LLM classification, or explicit multi-signal resolution.

## 6. The error message for mixed-family prompts is misleading

### User-visible symptom

- The UI said:
- `That request is outside the current planner scope. Supported families are email, calendar, metadata, hardware, and document Q&A.`

### What happened

- The request was actually in scope, but the classifier could not disambiguate it.

### External impact

- The message implies the user asked for something unsupported, when the real issue is planner ambiguity or a classifier limitation.
- This makes the product feel less capable than it is.

### Likely fix area

- Return a clarification or disambiguation message instead of an out-of-scope message.

## 7. Some console prompts hang with no visible completion even while services remain healthy

### User-visible symptom

- The user sends a prompt in the Agent Console.
- The user message renders in the chat.
- No agent reply appears, and the UI appears stuck waiting indefinitely.

### Example observed prompt

- `Draft an email to Joe telling him about the 2pm appointment with Alex.`

### What happened

- At the time of the hang:
- frontend container was up
- orchestrator container was healthy
- tools-app container was healthy
- Ollama was healthy
- there were no recent orchestrator log entries corresponding to the hung UI action

### External impact

- From the user's perspective, the app looks frozen or non-responsive.
- This is worse than a visible validation error because there is no clear recovery path in the UI.

### Likely fix area

- Frontend request lifecycle and loading/error state handling
- Network request timeout/cancellation handling in the UI
- Backend request logging for inbound `/agent/query` requests
- End-to-end integration coverage for "user submits prompt and gets either a result or a visible error"

## 8. Local deployment docs were directionally correct but not enough for a frictionless first run

### User-visible symptom

- A user relying only on the documented bootstrap flow would still hit setup and runtime issues.

### What happened

- Host Ollama requirement was real.
- Startup ordering was not robust enough.
- Timeout defaults were too low for local CPU inference.

### External impact

- First-time local deployment experience is rougher than the docs imply.

### Likely fix area

- Strengthen `DEPLOY.md` troubleshooting and prerequisites.
- Make the one-command bootstrap path succeed without manual intervention on supported environments.

## Recommended priority order

1. Fix planner family classification so common email-with-meeting phrasing works.
2. Investigate and fix chat requests that hang without a visible result or error.
3. Fix startup ordering and healthchecks so first boot is deterministic.
4. Raise or configure Ollama HTTP timeouts for CPU hosts.
5. Improve bootstrap prerequisite checks and docs for host Ollama.

## Developer Action Checklist

- Rework `PlannerAgentService.detectTaskFamily` so mixed-signal prompts do not default to `unsupported`.
- Add tests for prompts that contain both email and calendar vocabulary but are clearly one intended task.
- Add backend request logging around inbound `/agent/query` handling so silent UI hangs can be correlated with server activity.
- Add frontend integration coverage for:
- prompt submitted
- request in-flight state shown
- successful response rendered
- backend error rendered
- request timeout rendered
- Investigate whether the Agent Console sometimes fails to dispatch the request or fails to render the response/error after dispatch.
- Add proper compose healthchecks for `tools-app` and `orchestrator-app`.
- Make orchestrator startup depend on a healthy tools service, not merely a started one.
- Make Ollama HTTP timeouts configurable and set sane defaults for CPU-only local hosts.
- Consider a model warmup path or lighter local default model for the first request.
- Improve `scripts/deploy.py` and `DEPLOY.md` so host-Ollama requirements are detected and explained before bootstrap fails.
