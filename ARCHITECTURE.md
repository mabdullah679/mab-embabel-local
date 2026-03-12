# Architecture

This document describes the system as it exists now.

## Stack

1. React frontend
2. Spring orchestrator
3. Spring MCP tools service
4. Ollama
5. Postgres + pgvector
6. Prometheus + Grafana

Each layer has a separate responsibility:

- frontend: user interaction and inspection
- orchestrator: intent routing, guardrails, answer synthesis
- tools service: business execution and retrieval work
- Ollama: generation and embeddings
- Postgres + pgvector: structured records and vector search
- observability: health and metrics

## Frontend

The frontend provides:

- `Agent Console`
- `Calendar Viewer`
- `Email Mockup`
- `Metadata Inspector`

The chat view now also exposes a RAG inspector inside tool traces. For RAG requests it shows:

- dense hits
- lexical hits
- fused shortlist
- reranked shortlist
- final context chunks

The frontend does not decide which tool runs. It sends the prompt to the orchestrator and renders the result and traces.

## Orchestrator

The orchestrator is the planner entrypoint.

Current responsibilities:

- receive `POST /agent/query`
- detect the supported intent
- apply guardrails
- invoke exactly one MCP tool when needed
- synthesize the final user-facing answer

For RAG requests, the orchestrator no longer answers from a raw top hit. It consumes the richer RAG artifact returned by the tools service and builds the final answer from explicit assembled context.

## Tools Service

The tools service is the execution boundary.

It exposes:

- Spring MCP tools for agent-to-tool calls
- REST endpoints for browser reads and writes

Current tool domains:

- email drafting
- calendar item creation
- metadata lookup
- hardware search
- RAG retrieval

## MCP

The system uses real Spring MCP over SSE between the orchestrator and tools service.

The main code locations are:

- [AgentTools.java](/c:/Users/muham/Documents/mab-embabel-local/tools-app/src/main/java/com/mab/tools/mcp/AgentTools.java)
- [McpToolConfiguration.java](/c:/Users/muham/Documents/mab-embabel-local/tools-app/src/main/java/com/mab/tools/config/McpToolConfiguration.java)
- [ToolsClient.java](/c:/Users/muham/Documents/mab-embabel-local/orchestrator-app/src/main/java/com/mab/orchestrator/client/ToolsClient.java)

Browser traffic still uses normal REST APIs. The browser is not an MCP client.

## Shared Contracts

Shared DTOs live in `shared-models`.

That module now includes richer RAG response types so retrieval traces can preserve staged artifacts rather than only a final document list.

## Data Layer

Postgres stores:

- calendar items
- contacts
- email drafts
- metadata objects
- hardware records
- RAG source documents
- RAG chunks
- app state

pgvector is used for dense similarity search on chunk embeddings.

PostgreSQL full-text search is used for lexical chunk retrieval.

## Current RAG Design

The current RAG pipeline is:

1. ingest raw text
2. normalize text
3. chunk with deterministic overlap
4. store one source document row plus multiple chunk rows
5. embed each chunk with `nomic-embed-text`
6. retrieve dense candidates with pgvector
7. retrieve lexical candidates with full-text search
8. fuse rankings with reciprocal-rank-style scoring
9. rerank the bounded shortlist with `qwen2.5:7b-instruct`
10. assemble final context with dedupe and budget limits
11. generate the user-facing answer from that explicit context

The RAG response now carries:

- dense candidates
- lexical candidates
- fused candidates
- reranked candidates
- selected context chunks
- answer-context summary

## Ollama

Ollama runs locally and currently provides:

- `qwen2.5:7b-instruct` for summarization, reranking, and answer synthesis
- `nomic-embed-text` for embeddings

Ollama is not the source of truth for application data. It supports reasoning and retrieval quality around data already stored in Postgres.

## Observability

Observability is provided through:

- Spring Actuator
- Prometheus
- Grafana

This covers service health, request metrics, latency, JVM metrics, and basic dashboards.

## End-To-End Flow

The main request flow is:

1. the user sends a prompt in the frontend
2. the frontend sends it to the orchestrator
3. the orchestrator selects a supported task
4. when needed, the orchestrator invokes an MCP tool
5. the tools service reads or writes Postgres data
6. for RAG, the tools service performs hybrid retrieval and context assembly
7. the orchestrator returns a user-facing answer and tool traces
8. the frontend renders the answer and any structured trace views

## Still Local or Simplified

- email sending is still a local draft state transition
- calendar data is local app data
- contact management is local app data
- planner execution remains single-tool per request

## One-Line Summary

This is a local agent system where a Spring orchestrator plans, a Spring MCP tools service executes, Ollama handles generation and embeddings, Postgres stores both application and RAG data, and the frontend exposes both answers and retrieval internals.
