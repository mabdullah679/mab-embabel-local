.PHONY: up down rebuild ps logs wait pull-models bootstrap plan

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
	docker compose logs -f --tail=200 orchestrator-app

wait:
	powershell -ExecutionPolicy Bypass -File scripts/wait-health.ps1

pull-models:
	$(DEPLOY) plan

bootstrap:
	$(DEPLOY) bootstrap

plan:
	$(DEPLOY) plan
