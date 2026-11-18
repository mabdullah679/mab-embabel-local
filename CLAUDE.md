# mab-embabel-local (PUBLIC repo)

React chat UI -> Spring Boot orchestrator -> external Spring consumer API (:8080) + Ollama.
Master rules: `~/.claude/CLAUDE.md`. Project facts: README.md, ARCHITECTURE.md, DEPLOY.md.

## Commands (run 2026-11-18)
- Java: `mvn -B package` (JDK 17+; Dockerfile builds with 21). No tests exist.
- Frontend: `cd frontend && npm ci && npm run build`
- Images: `docker compose build`
- Run: `python scripts/deploy.py bootstrap` (needs Ollama + consumer API; NOT run in sanitize pass because Ollama was off-limits)

## Rules
- Public repo: no client names, internal URLs, emails (seed data uses `@example.local`), or credentials. Grafana admin/admin is a local default.
- Don't use qwen3-coder here. The app's models are `qwen2.5:7b-instruct` / `qwen2.5:3b` (see README).
- `ToolsClient` is a legacy name for the consumer REST client. Repo name says Embabel but no code uses it; don't claim otherwise.
- `docker/postgres-init/` is unused leftover (owner decision pending). Never commit `target/`, `dist/`, `__pycache__/`.
- Windows-origin: `make wait` needs PowerShell.
