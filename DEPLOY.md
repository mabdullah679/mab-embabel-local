# Deployment Guide

This repo includes a deployment orchestrator that detects the host environment and chooses the correct deployment strategy for the stack.

The goal is:
- one command to start the stack
- the right Ollama tactic for the host
- no manual compose-file selection for normal usage

## What Gets Deployed

The stack includes:
- `postgres`
- `tools-app`
- `orchestrator-app`
- `frontend`
- `prometheus`
- `grafana`
- `ollama` when the selected strategy uses containerized Ollama

## Deployment Strategies

The launcher in [scripts/deploy.py](/C:/Users/muham/Documents/mab-embabel-local/scripts/deploy.py) selects one of these strategies:

### 1. Windows/Linux with NVIDIA available

Uses:
- [docker-compose.yml](/C:/Users/muham/Documents/mab-embabel-local/docker-compose.yml)
- [docker-compose.nvidia.yml](/C:/Users/muham/Documents/mab-embabel-local/docker-compose.nvidia.yml)

Behavior:
- runs Ollama in Docker
- enables NVIDIA GPU passthrough
- points app services to `http://ollama:11434`

### 2. Apple Silicon macOS

Uses:
- [docker-compose.yml](/C:/Users/muham/Documents/mab-embabel-local/docker-compose.yml)
- [docker-compose.host-ollama.yml](/C:/Users/muham/Documents/mab-embabel-local/docker-compose.host-ollama.yml)

Behavior:
- expects Ollama to run natively on the Mac host
- points app services to `http://host.docker.internal:11434`

### 3. Other hosts

Uses the host-Ollama strategy by default.

Behavior:
- expects a host-native Ollama instance at `http://localhost:11434`
- defaults to a smaller generation model profile for better CPU responsiveness

## Prerequisites

Required on all hosts:
- Docker Desktop or Docker Engine with Compose
- Python 3

Required for the selected strategy:
- NVIDIA hosts:
  - working Docker GPU support
  - `nvidia-smi` available on the host
- Host-Ollama strategies:
  - Ollama installed on the host
  - Ollama running on port `11434`

Recommended models:
- GPU / stronger hosts:
  - `qwen2.5:7b-instruct`
  - fallback: `qwen2.5:3b`
- CPU / weaker hosts:
  - `qwen2.5:3b`
  - fallback: `qwen2.5:1.5b`
- `nomic-embed-text`

The bootstrap flow selects a model profile automatically, pulls the active generation model, its fallback, and the embedding model, then warms the generation models once so the first agent request is less likely to stall.

## Main Commands

Preview the selected deployment strategy:

```bash
python scripts/deploy.py plan
```

Run prerequisite checks before startup:

```bash
python scripts/deploy.py preflight
```

Start the stack without rebuilding:

```bash
python scripts/deploy.py up
```

Rebuild and start the stack:

```bash
python scripts/deploy.py rebuild
```

Run the full bootstrap flow:

```bash
python scripts/deploy.py bootstrap
```

Stop the stack:

```bash
python scripts/deploy.py down
```

Show running services:

```bash
python scripts/deploy.py ps
```

Equivalent Make targets are available:

```bash
make plan
make up
make rebuild
make bootstrap
make down
make ps
```

## Recommended First Run

From the repo root:

```bash
python scripts/deploy.py plan
python scripts/deploy.py preflight
python scripts/deploy.py bootstrap
```

`bootstrap` will:
1. detect the deployment strategy
2. start the correct compose stack
3. wait for service health
4. pull Ollama models
5. warm the selected generation models
6. seed sample RAG documents

## URLs

After startup:
- Frontend: `http://localhost:5173`
- Orchestrator: `http://localhost:8081`
- Tools API: `http://localhost:8082`
- Ollama API: `http://localhost:11434`
- Prometheus: `http://localhost:9090`
- Grafana: `http://localhost:3000`

## How To Verify Deployment

Check the selected strategy:

```bash
python scripts/deploy.py plan
```

Check running services:

```bash
python scripts/deploy.py ps
```

Check health:

```bash
powershell -ExecutionPolicy Bypass -File scripts/wait-health.ps1
```

Check Ollama models:

NVIDIA-container strategy:

```bash
docker exec mab-ollama ollama list
docker exec mab-ollama ollama ps
```

Host-Ollama strategy:

```bash
ollama list
ollama ps
```

## Example Validation Requests

RAG example:

```bash
curl -X POST http://localhost:8081/agent/query \
  -H "Content-Type: application/json" \
  -d '{"query":"what do the docs say about planner agents?"}'
```

Draft listing:

```bash
curl http://localhost:8082/api/email/drafts
```

## Troubleshooting

### Compose interpolation error

If Compose reports an interpolation problem, make sure you are using the latest checked-in compose files. The valid syntax is:

```text
${OLLAMA_BASE_URL:-http://host.docker.internal:11434}
```

### Host-Ollama strategy selected, but startup fails

That usually means Ollama is not installed or not running on the host.

Verify:

```bash
ollama list
```

and:

```bash
curl http://localhost:11434/api/tags
```

If `ollama` is missing entirely:
- macOS: install from `https://ollama.com/download` or `brew install --cask ollama`
- Windows: install from `https://ollama.com/download`
- Linux: install from `https://ollama.com/download` or your package manager, then run `ollama serve`

### NVIDIA host selected, but Ollama is still on CPU

Verify on the host:

```bash
nvidia-smi
```

Verify in the container:

```bash
docker exec mab-ollama nvidia-smi
docker exec mab-ollama ollama ps
```

If `ollama ps` shows `PROCESSOR 100% CPU`, GPU passthrough is not active yet.

### Orchestrator requests fail but services are up

Check logs:

```bash
docker logs -f mab-orchestrator-app
docker logs -f mab-tools-app
docker logs -f mab-ollama
```

The app containers now wait on actuator readiness healthchecks, so first boot should be deterministic. If startup still stalls, inspect:

```bash
python scripts/deploy.py ps
```

### Re-seed local RAG data

```bash
powershell -ExecutionPolicy Bypass -File scripts/seed-rag.ps1
```

## Deployment Files

- [docker-compose.yml](/C:/Users/muham/Documents/mab-embabel-local/docker-compose.yml)
- [docker-compose.nvidia.yml](/C:/Users/muham/Documents/mab-embabel-local/docker-compose.nvidia.yml)
- [docker-compose.host-ollama.yml](/C:/Users/muham/Documents/mab-embabel-local/docker-compose.host-ollama.yml)
- [scripts/deploy.py](/C:/Users/muham/Documents/mab-embabel-local/scripts/deploy.py)
- [Makefile](/C:/Users/muham/Documents/mab-embabel-local/Makefile)
