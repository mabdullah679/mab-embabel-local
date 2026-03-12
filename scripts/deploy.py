#!/usr/bin/env python3
from __future__ import annotations

import argparse
import concurrent.futures
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
DEFAULT_OLLAMA_CONNECT_TIMEOUT = "4s"
DEFAULT_OLLAMA_READ_TIMEOUT = "45s"
DEFAULT_PRIMARY_WARMUP_TIMEOUT_SECONDS = 90
DEFAULT_FALLBACK_WARMUP_TIMEOUT_SECONDS = 30
DEFAULT_WARMUP_PROGRESS_INTERVAL_SECONDS = 5
MIN_PYTHON = (3, 9)
RAG_SEED_DOCS = [
    "Spring MCP tools are orchestrated by a planner agent in this local architecture.",
    "The orchestrator receives natural language queries and routes them to typed tools.",
    "RAG retrieval uses pgvector similarity search with embeddings from nomic-embed-text.",
]
MODEL_PROFILES = {
    "gpu": {
        "generation": "qwen2.5:7b-instruct",
        "fallback": "qwen2.5:3b",
        "embedding": "nomic-embed-text",
    },
    "balanced": {
        "generation": "qwen2.5:3b",
        "fallback": "qwen2.5:1.5b",
        "embedding": "nomic-embed-text",
    },
}


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


def request_raw(url: str, method: str = "GET", body: dict | None = None, timeout: int = 20) -> str:
    payload = None if body is None else json.dumps(body).encode("utf-8")
    req = urllib.request.Request(url, data=payload, method=method)
    if payload is not None:
        req.add_header("Content-Type", "application/json")
    with urllib.request.urlopen(req, timeout=timeout) as response:
        return response.read().decode("utf-8")


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


def check_python_version() -> None:
    if sys.version_info < MIN_PYTHON:
        required = ".".join(map(str, MIN_PYTHON))
        current = ".".join(map(str, sys.version_info[:3]))
        raise SystemExit(f"Python {required}+ is required, but found {current}.")


def check_docker_available() -> None:
    if not is_command_available("docker"):
        raise SystemExit("Docker is required, but the `docker` command is not installed or not on PATH.")


def check_docker_compose_available() -> None:
    try:
        run(["docker", "compose", "version"], capture=True)
    except Exception as exc:
        raise SystemExit("Docker Compose is required, but `docker compose` is not available.") from exc


def check_docker_daemon() -> None:
    try:
        run(["docker", "info"], capture=True)
    except Exception as exc:
        raise SystemExit("Docker is installed, but the Docker daemon is not reachable. Start Docker Desktop/Engine and retry.") from exc


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


def docker_supports_nvidia() -> bool:
    try:
        info = run(["docker", "info", "--format", "{{json .Runtimes}}"], capture=True).stdout.strip()
        if not info:
            return False
        runtimes = json.loads(info)
        return isinstance(runtimes, dict) and "nvidia" in runtimes
    except Exception:
        return False


def detect_host_active_generation_model() -> str | None:
    try:
        response = request_json(f"{DEFAULT_OLLAMA_URL}/api/ps", timeout=10)
        models = response.get("models") or []
        for model in models:
            if not isinstance(model, dict):
                continue
            name = str(model.get("name") or "").strip()
            if name and name != "nomic-embed-text":
                return name
    except Exception:
        return None
    return None


def host_ollama_install_hint() -> str:
    system = platform.system().lower()
    if system == "darwin":
        return "Install Ollama from https://ollama.com/download or with `brew install --cask ollama`, then start the Ollama app."
    if system == "windows":
        return "Install Ollama from https://ollama.com/download and ensure the Ollama desktop app is running."
    return "Install Ollama from https://ollama.com/download or your package manager, then start `ollama serve`."


def apply_model_profile(env: dict[str, str], profile_name: str) -> dict[str, str]:
    profile = MODEL_PROFILES[profile_name]
    env["OLLAMA_GENERATION_MODEL"] = profile["generation"]
    env["OLLAMA_FALLBACK_MODEL"] = profile["fallback"]
    env["OLLAMA_EMBEDDING_MODEL"] = profile["embedding"]
    env.setdefault("OLLAMA_CONNECT_TIMEOUT", DEFAULT_OLLAMA_CONNECT_TIMEOUT)
    env.setdefault("OLLAMA_READ_TIMEOUT", DEFAULT_OLLAMA_READ_TIMEOUT)
    env["OLLAMA_MODEL_PROFILE"] = profile_name
    return env


def finalize_declared_models(strategy: str, env: dict[str, str]) -> dict[str, str]:
    if strategy != "nvidia-container-ollama" and host_ollama_ready():
        active_model = detect_host_active_generation_model()
        if active_model:
            env["OLLAMA_GENERATION_MODEL"] = active_model
    return env


def detect_strategy() -> tuple[str, list[Path], dict[str, str]]:
    system = platform.system().lower()
    machine = platform.machine().lower()
    env = os.environ.copy()
    env.setdefault("COMPOSE_PROJECT_NAME", "mab-embabel-local")

    if system == "darwin" and machine in {"arm64", "aarch64"}:
        env["OLLAMA_BASE_URL"] = "http://host.docker.internal:11434"
        strategy = "host-ollama-mac"
        return strategy, [BASE_COMPOSE, HOST_OLLAMA_COMPOSE], finalize_declared_models(strategy, apply_model_profile(env, "gpu"))

    if has_nvidia_host() and system in {"windows", "linux"}:
        strategy = "nvidia-container-ollama"
        return strategy, [BASE_COMPOSE, NVIDIA_COMPOSE], finalize_declared_models(strategy, apply_model_profile(env, "gpu"))

    env["OLLAMA_BASE_URL"] = "http://host.docker.internal:11434"
    strategy = "host-ollama-generic"
    return strategy, [BASE_COMPOSE, HOST_OLLAMA_COMPOSE], finalize_declared_models(strategy, apply_model_profile(env, "balanced"))


def compose_command(files: list[Path], args: list[str]) -> list[str]:
    cmd = ["docker", "compose"]
    for file in files:
        cmd.extend(["-f", str(file)])
    cmd.extend(args)
    return cmd


def pull_models(strategy: str, env: dict[str, str]) -> None:
    models = [
        env["OLLAMA_GENERATION_MODEL"],
        env["OLLAMA_FALLBACK_MODEL"],
        env["OLLAMA_EMBEDDING_MODEL"],
    ]
    if strategy == "nvidia-container-ollama":
        for model in models:
            run(["docker", "exec", "mab-ollama", "ollama", "pull", model])
        return

    if not is_command_available("ollama"):
        raise SystemExit("Host Ollama is required for this deployment strategy, but the `ollama` command is not installed. " + host_ollama_install_hint())
    for model in models:
        run(["ollama", "pull", model])


def warm_models(env: dict[str, str]) -> None:
    ollama_url = env.get("OLLAMA_BASE_URL", DEFAULT_OLLAMA_URL).replace("http://host.docker.internal:11434", DEFAULT_OLLAMA_URL)
    models = [
        (env["OLLAMA_GENERATION_MODEL"], DEFAULT_PRIMARY_WARMUP_TIMEOUT_SECONDS),
        (env["OLLAMA_FALLBACK_MODEL"], DEFAULT_FALLBACK_WARMUP_TIMEOUT_SECONDS),
    ]
    for model, timeout_seconds in models:
        print(f"Warming model: {model}")
        try:
            with concurrent.futures.ThreadPoolExecutor(max_workers=1) as executor:
                started = time.monotonic()
                future = executor.submit(
                    request_raw,
                    f"{ollama_url}/api/generate",
                    "POST",
                    {
                        "model": model,
                        "prompt": "ready",
                        "stream": False,
                        "options": {"num_predict": 1}
                    },
                    timeout_seconds,
                )
                while True:
                    try:
                        future.result(timeout=DEFAULT_WARMUP_PROGRESS_INTERVAL_SECONDS)
                        break
                    except concurrent.futures.TimeoutError:
                        elapsed = int(time.monotonic() - started)
                        if elapsed >= timeout_seconds:
                            raise TimeoutError(f"timed out after {timeout_seconds}s")
                        print(f"  still warming {model}... {elapsed}s elapsed")
            print(f"Warmed model: {model}")
        except KeyboardInterrupt:
            print("\nWarmup interrupted. Continuing without completing model warmup.")
            return
        except Exception as exc:
            print(f"Warning: model warmup failed for {model}: {exc}")


def ensure_host_ollama(strategy: str) -> None:
    if strategy == "nvidia-container-ollama":
        return
    if not is_command_available("ollama"):
        raise SystemExit(
            "This deployment strategy expects host-native Ollama, but the `ollama` command is not installed. "
            + host_ollama_install_hint()
        )
    if not host_ollama_ready():
        raise SystemExit(
            "This deployment strategy expects Ollama running on the host at http://localhost:11434. "
            + host_ollama_install_hint()
        )


def ensure_nvidia_runtime() -> None:
    if not has_nvidia_host():
        raise SystemExit("NVIDIA deployment was selected, but `nvidia-smi` is unavailable or not working on the host.")
    if not docker_supports_nvidia():
        raise SystemExit("NVIDIA deployment was selected, but Docker does not report an `nvidia` runtime. Install/configure NVIDIA container support and retry.")


def run_preflight(strategy: str) -> None:
    print("Running preflight checks...")
    check_python_version()
    print(f"  Python: {sys.version.split()[0]}")
    check_docker_available()
    print("  Docker CLI: available")
    check_docker_compose_available()
    print("  Docker Compose: available")
    check_docker_daemon()
    print("  Docker daemon: reachable")

    if strategy == "nvidia-container-ollama":
        ensure_nvidia_runtime()
        print("  NVIDIA host/runtime: ready")
    else:
        ensure_host_ollama(strategy)
        print(f"  Host Ollama: reachable at {DEFAULT_OLLAMA_URL}")


def print_plan(strategy: str, files: list[Path], env: dict[str, str]) -> None:
    print(f"Deployment strategy: {strategy}")
    print("Compose files:")
    for file in files:
        print(f"  - {file.name}")
    print(f"OLLAMA_BASE_URL: {env.get('OLLAMA_BASE_URL', 'http://ollama:11434')}")
    print(f"OLLAMA_GENERATION_MODEL: {env.get('OLLAMA_GENERATION_MODEL')}")
    print(f"OLLAMA_FALLBACK_MODEL: {env.get('OLLAMA_FALLBACK_MODEL')}")
    print(f"OLLAMA_READ_TIMEOUT: {env.get('OLLAMA_READ_TIMEOUT')}")
    print("Model authority: deployment environment")
    if strategy != "nvidia-container-ollama":
        if not is_command_available("ollama"):
            print(f"Host Ollama status: missing `ollama` command. {host_ollama_install_hint()}")
        elif not host_ollama_ready():
            print(f"Host Ollama status: installed, but not responding at {DEFAULT_OLLAMA_URL}. Start Ollama before `up` or `bootstrap`.")
        else:
            print("Host Ollama status: ready")


def do_up(files: list[Path], env: dict[str, str], build: bool) -> None:
    args = ["up", "-d"]
    if build:
        args.insert(1, "--build")
    run(compose_command(files, args), env=env)


def do_up_services(files: list[Path], env: dict[str, str], services: list[str]) -> None:
    run(compose_command(files, ["up", "-d", *services]), env=env)


def do_down(files: list[Path], env: dict[str, str]) -> None:
    run(compose_command(files, ["down"]), env=env)


def do_ps(files: list[Path], env: dict[str, str]) -> None:
    run(compose_command(files, ["ps"]), env=env)


def bootstrap(strategy: str, files: list[Path], env: dict[str, str]) -> None:
    ensure_host_ollama(strategy)
    if strategy == "nvidia-container-ollama":
        do_up_services(files, env, ["postgres", "ollama"])
        pull_models(strategy, env)
        warm_models(env)
        do_up(files, env, build=True)
    else:
        pull_models(strategy, env)
        warm_models(env)
        do_up(files, env, build=True)
    wait_for_health()
    seed_rag()


def main() -> None:
    parser = argparse.ArgumentParser(description="Dynamic deployment orchestrator for mab-embabel-local")
    parser.add_argument("command", choices=["plan", "preflight", "up", "rebuild", "down", "ps", "bootstrap"])
    args = parser.parse_args()

    strategy, files, env = detect_strategy()

    if args.command == "plan":
        print_plan(strategy, files, env)
        return

    if args.command == "preflight":
        print_plan(strategy, files, env)
        run_preflight(strategy)
        return

    if args.command == "up":
        print_plan(strategy, files, env)
        run_preflight(strategy)
        do_up(files, env, build=False)
        return

    if args.command == "rebuild":
        print_plan(strategy, files, env)
        run_preflight(strategy)
        do_up(files, env, build=True)
        return

    if args.command == "down":
        print_plan(strategy, files, env)
        do_down(files, env)
        return

    if args.command == "ps":
        print_plan(strategy, files, env)
        run_preflight(strategy)
        do_ps(files, env)
        return

    if args.command == "bootstrap":
        print_plan(strategy, files, env)
        run_preflight(strategy)
        bootstrap(strategy, files, env)


if __name__ == "__main__":
    main()
