.PHONY: up down rebuild ps logs wait pull-models seed-rag bootstrap plan

DEPLOY = python scripts/deploy.py

up:
	$(DEPLOY) up

down:
	$(DEPLOY) down

rebuild:
	$(DEPLOY) rebuild

ps:
	$(DEPLOY) ps

logs:
	docker compose logs -f --tail=200 orchestrator-app tools-app

wait:
	powershell -ExecutionPolicy Bypass -File scripts/wait-health.ps1

pull-models:
	$(DEPLOY) plan

seed-rag:
	powershell -ExecutionPolicy Bypass -File scripts/seed-rag.ps1

bootstrap:
	$(DEPLOY) bootstrap

plan:
	$(DEPLOY) plan
