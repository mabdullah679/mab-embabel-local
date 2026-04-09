# Deployment Guide

This project ships a local stack made of:
- `frontend`
- `orchestrator-app`
- `ollama`
- `prometheus`
- `grafana`

The application also depends on an external local service:
- Spring consumer API on `http://localhost:8080`

The Spring consumer API is not started by this repo. It must already be running before the app is useful.

## What Gets Deployed

`python scripts/deploy.py bootstrap` brings up:
- `orchestrator-app`
- `frontend`
- `prometheus`
- `grafana`
- `ollama` when the selected deployment strategy uses containerized Ollama

## Deployment Strategies

The launcher in `scripts/deploy.py` chooses one of these strategies automatically.

### 1. Windows/Linux with NVIDIA available

Uses:
- `docker-compose.yml`
- `docker-compose.nvidia.yml`

Behavior:
- runs Ollama in Docker
- enables NVIDIA GPU passthrough
- points the orchestrator to `http://ollama:11434`

### 2. Apple Silicon macOS

Uses:
- `docker-compose.yml`
- `docker-compose.host-ollama.yml`

Behavior:
- expects Ollama to run on the host
- points the orchestrator to `http://host.docker.internal:11434`

### 3. Other hosts

Uses:
- `docker-compose.yml`
- `docker-compose.host-ollama.yml`

Behavior:
- expects Ollama on the host at `http://localhost:11434`
- uses the host-Ollama path by default

## Prerequisites

Required on all hosts:
- Docker Desktop or Docker Engine with Compose
- Python 3
- Spring consumer API running on `http://localhost:8080`

Required for the selected Ollama strategy:
- NVIDIA strategy:
  - `nvidia-smi`
  - working Docker GPU support
- host-Ollama strategies:
  - Ollama installed on the host
  - Ollama running on port `11434`

Expected Ollama models:
- `qwen2.5:7b-instruct`
- `qwen2.5:3b`

## Main Commands

Preview the deployment plan:

```bash
python scripts/deploy.py plan
```

Run preflight checks:

```bash
python scripts/deploy.py preflight
```

Start without rebuilding:

```bash
python scripts/deploy.py up
```

Rebuild and start:

```bash
python scripts/deploy.py rebuild
```

Run the recommended first-time bootstrap:

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

Equivalent `Makefile` targets:

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
1. detect the Ollama deployment strategy
2. run preflight checks
3. pull and warm the configured Ollama models
4. build and start the orchestrator and frontend
5. wait for orchestrator health

## URLs

After startup:
- Frontend: `http://localhost:5173`
- Orchestrator: `http://localhost:8081`
- Spring consumer API: `http://localhost:8080`
- Ollama API: `http://localhost:11434`
- Prometheus: `http://localhost:9090`
- Grafana: `http://localhost:3000`

## Verification

Check deployment status:

```bash
python scripts/deploy.py ps
```

Check orchestrator health:

```bash
powershell -ExecutionPolicy Bypass -File scripts/wait-health.ps1
```

Check the Spring consumer API directly:

```bash
curl http://localhost:8080/api/health
curl http://localhost:8080/api/records/4
```

Check the orchestrator path:

```bash
curl -X POST http://localhost:8081/agent/query \
  -H "Content-Type: application/json" \
  -d '{"query":"show record 4","history":[]}'
```

## Troubleshooting

### Spring consumer API is not running

The app depends on the Spring consumer API but does not start it.

Verify:

```bash
curl http://localhost:8080/api/health
```

If that fails, start the Spring consumer stack first.

### Host-Ollama strategy selected, but startup fails

That usually means Ollama is not installed or not running on the host.

Verify:

```bash
ollama list
curl http://localhost:11434/api/tags
```

### NVIDIA strategy selected, but Ollama is not using the GPU

Verify on the host:

```bash
nvidia-smi
```

Verify in the container:

```bash
docker exec domain-ollama nvidia-smi
docker exec domain-ollama ollama ps
```

### Orchestrator is up, but answers fail

Check:
- `docker logs -f domain-orchestrator-app`
- `docker logs -f domain-ollama`
- Spring consumer API health on `http://localhost:8080/api/health`

The most common causes are:
- Spring consumer API unavailable
- Ollama unavailable
- model warmup still in progress on slower hardware

## Deployment Files

- `docker-compose.yml`
- `docker-compose.nvidia.yml`
- `docker-compose.host-ollama.yml`
- `scripts/deploy.py`
- `scripts/wait-health.ps1`
- `Makefile`
