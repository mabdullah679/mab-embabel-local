MAB-EMBABEL-LOCAL

What it is: a chat app that runs on your own computer. You type a question like "show record 4". A web page sends it to a Spring Boot service called the orchestrator. The orchestrator fetches the matching data from a separate "consumer" service and asks a local Ollama AI model to write a plain answer from that data. A trace of what it called is shown with the answer. Prometheus and Grafana show metrics.

What is not included: the consumer service on port 8080 is not in this repo. You must run it yourself. You also need Ollama with the models qwen2.5:7b-instruct and qwen2.5:3b. Despite the repo name, nothing here uses the Embabel framework; no file mentions it.

What you need: Docker with Compose, Python 3, and free ports 3000, 5173, 8081 and 9090. Java, Maven and Node are not needed to run it, because Docker builds them. For building by hand you need JDK 17 or newer, Maven and Node 20 or newer.

How to run:
1. Start the consumer service and Ollama, and pull both models.
2. Run: python scripts/deploy.py plan to preview what it will do.
3. Run: python scripts/deploy.py bootstrap (or make bootstrap).
4. Open http://localhost:5173 and ask "show record 4".
If it will not start: the orchestrator refuses to start when Ollama is unreachable or a model is missing. DEPLOY.md explains the host-Ollama and NVIDIA options. You can change the service addresses with SPRING_CONSUMER_BASE_URL and OLLAMA_BASE_URL. The make wait command only works on Windows.

What it does: a question about one record fetches that record. A broad question fetches all records. A health question checks the consumer. Which of the three happens is decided by simple pattern matching in PlannerAgentService, not by the AI. The AI only writes the final wording. Grafana is at port 3000 with login admin and password admin.

Tests: there are none. The automatic checks on pull requests only build the Java and web code and the Docker images, and scan for leaked secrets. Nothing automatic tests the chat itself, because that needs Ollama and the consumer service.

Choices worth knowing: data comes only from the consumer service, not a local database. The folder docker/postgres-init is a leftover from an older design and nothing uses it. Prometheus and Grafana use the latest image and Grafana has the default password, which is fine on your own machine but not for exposing.
