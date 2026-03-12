#!/usr/bin/env python3
from __future__ import annotations

import argparse
import json
import os
import platform
import shutil
import subprocess
import sys
import time
import urllib.error
import urllib.request
from pathlib import Path


ROOT = Path(__file__).resolve().parent.parent
BASE_COMPOSE = ROOT / "docker-compose.yml"
NVIDIA_COMPOSE = ROOT / "docker-compose.nvidia.yml"
HOST_OLLAMA_COMPOSE = ROOT / "docker-compose.host-ollama.yml"
DEFAULT_TOOLS_URL = "http://localhost:8082"
DEFAULT_ORCH_HEALTH = "http://localhost:8081/actuator/health"
DEFAULT_TOOLS_HEALTH = "http://localhost:8082/actuator/health"
DEFAULT_OLLAMA_URL = "http://localhost:11434"
RAG_SEED_DOCS = [
    "Spring MCP tools are orchestrated by a planner agent in this local architecture.",
    "The orchestrator receives natural language queries and routes them to typed tools.",
    "RAG retrieval uses pgvector similarity search with embeddings from nomic-embed-text.",
]


def run(command: list[str], *, env: dict[str, str] | None = None, cwd: Path = ROOT, capture: bool = False) -> subprocess.CompletedProcess[str]:
    return subprocess.run(
        command,
        cwd=str(cwd),
        env=env,
        check=True,
        text=True,
        capture_output=capture,
    )


def request_json(url: str, method: str = "GET", body: dict | None = None, timeout: int = 20) -> dict:
    payload = None if body is None else json.dumps(body).encode("utf-8")
    req = urllib.request.Request(url, data=payload, method=method)
    if payload is not None:
        req.add_header("Content-Type", "application/json")
    with urllib.request.urlopen(req, timeout=timeout) as response:
        return json.loads(response.read().decode("utf-8"))


def wait_for_health(max_attempts: int = 90, sleep_seconds: int = 2) -> None:
    for _ in range(max_attempts):
        try:
            orch = request_json(DEFAULT_ORCH_HEALTH).get("status")
            tools = request_json(DEFAULT_TOOLS_HEALTH).get("status")
            if orch == "UP" and tools == "UP":
                print("Services are healthy")
                return
        except Exception:
            pass
        time.sleep(sleep_seconds)
    raise SystemExit("Services did not become healthy in time")


def seed_rag() -> None:
    for doc in RAG_SEED_DOCS:
        response = request_json(f"{DEFAULT_TOOLS_URL}/api/rag/ingest", method="POST", body={"content": doc}, timeout=120)
        print(f"Seeded RAG document: {response.get('id')}")


def is_command_available(command: str) -> bool:
    return shutil.which(command) is not None


def has_nvidia_host() -> bool:
    if not is_command_available("nvidia-smi"):
        return False
    try:
        run(["nvidia-smi"], capture=True)
        return True
    except Exception:
        return False


def host_ollama_ready() -> bool:
    try:
        request_json(f"{DEFAULT_OLLAMA_URL}/api/tags", timeout=10)
        return True
    except Exception:
        return False


def detect_strategy() -> tuple[str, list[Path], dict[str, str]]:
    system = platform.system().lower()
    machine = platform.machine().lower()
    env = os.environ.copy()
    env.setdefault("COMPOSE_PROJECT_NAME", "mab-embabel-local")

    if system == "darwin" and machine in {"arm64", "aarch64"}:
        env["OLLAMA_BASE_URL"] = "http://host.docker.internal:11434"
        return "host-ollama-mac", [BASE_COMPOSE, HOST_OLLAMA_COMPOSE], env

    if has_nvidia_host() and system in {"windows", "linux"}:
        return "nvidia-container-ollama", [BASE_COMPOSE, NVIDIA_COMPOSE], env

    env["OLLAMA_BASE_URL"] = "http://host.docker.internal:11434"
    return "host-ollama-generic", [BASE_COMPOSE, HOST_OLLAMA_COMPOSE], env


def compose_command(files: list[Path], args: list[str]) -> list[str]:
    cmd = ["docker", "compose"]
    for file in files:
        cmd.extend(["-f", str(file)])
    cmd.extend(args)
    return cmd


def pull_models(strategy: str) -> None:
    if strategy == "nvidia-container-ollama":
        run(["docker", "exec", "mab-ollama", "ollama", "pull", "qwen2.5:7b-instruct"])
        run(["docker", "exec", "mab-ollama", "ollama", "pull", "nomic-embed-text"])
        return

    if not is_command_available("ollama"):
        raise SystemExit("Host Ollama is required for this deployment strategy, but the `ollama` command is not installed.")
    run(["ollama", "pull", "qwen2.5:7b-instruct"])
    run(["ollama", "pull", "nomic-embed-text"])


def ensure_host_ollama(strategy: str) -> None:
    if strategy == "nvidia-container-ollama":
        return
    if not host_ollama_ready():
        raise SystemExit(
            "This deployment strategy expects Ollama running on the host at http://localhost:11434. "
            "Start host Ollama first, then rerun the deploy script."
        )


def print_plan(strategy: str, files: list[Path], env: dict[str, str]) -> None:
    print(f"Deployment strategy: {strategy}")
    print("Compose files:")
    for file in files:
        print(f"  - {file.name}")
    print(f"OLLAMA_BASE_URL: {env.get('OLLAMA_BASE_URL', 'http://ollama:11434')}")


def do_up(files: list[Path], env: dict[str, str], build: bool) -> None:
    args = ["up", "-d"]
    if build:
        args.insert(1, "--build")
    run(compose_command(files, args), env=env)


def do_down(files: list[Path], env: dict[str, str]) -> None:
    run(compose_command(files, ["down"]), env=env)


def do_ps(files: list[Path], env: dict[str, str]) -> None:
    run(compose_command(files, ["ps"]), env=env)


def bootstrap(strategy: str, files: list[Path], env: dict[str, str]) -> None:
    ensure_host_ollama(strategy)
    do_up(files, env, build=True)
    wait_for_health()
    pull_models(strategy)
    seed_rag()


def main() -> None:
    parser = argparse.ArgumentParser(description="Dynamic deployment orchestrator for mab-embabel-local")
    parser.add_argument("command", choices=["plan", "up", "rebuild", "down", "ps", "bootstrap"])
    args = parser.parse_args()

    strategy, files, env = detect_strategy()

    if args.command == "plan":
        print_plan(strategy, files, env)
        return

    if args.command == "up":
        ensure_host_ollama(strategy)
        print_plan(strategy, files, env)
        do_up(files, env, build=False)
        return

    if args.command == "rebuild":
        ensure_host_ollama(strategy)
        print_plan(strategy, files, env)
        do_up(files, env, build=True)
        return

    if args.command == "down":
        print_plan(strategy, files, env)
        do_down(files, env)
        return

    if args.command == "ps":
        print_plan(strategy, files, env)
        do_ps(files, env)
        return

    if args.command == "bootstrap":
        print_plan(strategy, files, env)
        bootstrap(strategy, files, env)


if __name__ == "__main__":
    main()
