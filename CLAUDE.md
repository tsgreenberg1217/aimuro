# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

AIMURO is a Spring Boot AI chatbot that answers Gundam Trading Card Game rules questions using RAG (Retrieval-Augmented Generation). It uses OpenAI (`o4-mini`) for generation and PostgreSQL + pgvector for semantic search over ingested rules documents.

## Build and Run Commands

```bash
# Build the project
./gradlew build

# Run locally (requires PostgreSQL running)
./gradlew bootRun

# Build Docker image and start all services (PostgreSQL + app)
./aimuro-build.sh
# or manually:
docker build -t aimuro-service .
docker-compose up -d

# Run with debug profile (uses in-memory SimpleVectorStore, no PostgreSQL needed)
./gradlew bootRun --args='--spring.profiles.active=debug'

# Run tests
./gradlew test

# Run a single test
./gradlew test --tests "com.aimuro.YourTestClass.yourTestMethod"
```

The app runs on port 8080 locally, mapped to port 8000 in Docker.

## Architecture

**RAG Pipeline:**
1. On startup, `IngestionService` reads rules documents from `src/main/resources/docs/` and stores chunked embeddings in the vector store.
2. `ChatBotConfiguration` wires up a `ChatClient` with retrieval advisors that query the vector store per user message.
3. `ChatController` exposes `/ask` (SSE streaming with resilience via Redis).

**SSE Resilience (ask/replay pattern):**
- `POST /ask` kicks off async generation on a virtual thread and immediately begins streaming via Redis. Returns a `requestId`.
- `ChatStreamProducer` writes AI response chunks to a Redis stream (`stream:{requestId}`). A sentinel `done=true` message signals completion.
- `ChatStreamConsumer` reads from the Redis stream with `takeWhile { done != "true" }`, terminating the SSE flux when the sentinel arrives.
- `GET /ask/{requestId}/stream` allows clients to reconnect mid-stream or replay a completed response.
- `StreamBufferService` tracks request state (`in_progress` / `complete` / `error`) in Redis with a 10-minute TTL.

**Document Ingestion:**
- `DocService` interface with implementations `MarkdownDocService` (splits on `## ` headers) and `PdfDocService` (splits on numbered section pattern).
- Documents are chunked with `TokenTextSplitter` (400 token chunks, 200 char min).
- Currently ingesting: `gundam_card_game_comprehensive_rules_v1_5_0.md`

**Retrieval Advisors in `ChatBotConfiguration`:**
- `SmallComprehensiveRulesAdvisor`: top-K=16, similarity threshold=0.75 (comprehensive rules markdown)
- `WebRulesAdvisor`: top-K=2, filter `detail_level == 'general'` (web rules RTF)
- Both wrap `QuestionAnswerAdvisor` inside a custom `GundamAdvisor` for logging.

**Profiles:**
- Default / `prod`: Uses PgVector + Redis + separate PostgreSQL for conversation history
- `debug`: Uses in-memory `SimpleVectorStore` via `DebugVectorStoreConfiguration`, excludes PostgreSQL and Redis

## Key Files

| File | Purpose |
|------|---------|
| `ChatController.kt` | REST endpoints (`/ask`, `/ask/{requestId}/stream`, `/conversation/{id}`) |
| `AimuroChatServiceImpl.kt` | Ask/replay orchestration, async generation |
| `ChatStreamProducer.kt` | Writes AI chunks + done sentinel to Redis stream |
| `ChatStreamConsumer.kt` | Reads Redis stream, terminates on done sentinel |
| `StreamBufferService.kt` | Tracks request state in Redis with TTL |
| `RedisConfiguration.kt` | `StreamReceiver` bean (100ms poll timeout) |
| `ConversationJpaConfiguration.kt` | Dual datasource config (pgvector + conversation DB) |
| `ChatBotConfiguration.kt` | ChatClient bean + advisor wiring |
| `IngestionService.kt` | Startup document ingestion into vector store |
| `GundamAdvisor.kt` | Logging wrapper around `QuestionAnswerAdvisor` |
| `MarkdownDocService.kt` | Markdown document reader/splitter |
| `PdfDocService.kt` | PDF document reader/splitter |
| `application.yaml` | Main config (DB URL, OpenAI key, active profile) |
| `application-debug.yaml` | Debug profile (in-memory vector store) |
| `DebugVectorStoreConfiguration.kt` | `SimpleVectorStore` bean for debug profile |

## API

All endpoints require a `conversationId`. `/ask` starts streaming immediately; `/ask/{requestId}/stream` reconnects to an in-progress or completed stream.

```json
POST /ask
GET  /ask/{requestId}/stream
GET  /conversation/{conversationId}
GET  /conversation/{conversationId}/status

POST /ask body:
{
  "conversationId": 1,
  "conversation": [
    {"role": "user", "content": "Can I attack directly?"},
    {"role": "assistant", "content": "..."}
  ]
}
```
Both `/ask` and `/ask/{requestId}/stream` return `text/event-stream` (SSE). Each event is a `RulesResponse` with `answer` (chunk text) and `isComplete` (true on the final event).

## Infrastructure

- **pgvector DB**: `pgvector/pgvector:pg18`, DB name `gundam-tcg-rules-vector-db`, user `postgres`, port 5432
- **Conversation DB**: `postgres:latest`, DB name `aimuro-conversation-db`, user `postgres`, port 5433 (host) → 5432 (container)
- **Redis**: `redis:7-alpine`, port 6379 — used for response stream buffering and request state
- **Docker port**: app maps `8080:8080` (changed from 8000)
- **Active profile in Docker**: `prod`
- **Schema**: pgvector schema auto-initialized by Spring AI; conversation schema managed by Hibernate (`hbm2ddl.auto=update`)
- **OpenAI API key**: Set via `OPEN_AI_KEY` env var in docker-compose
