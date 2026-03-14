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
./gradlew test --tests "com.aimuro.aimuro.YourTestClass.yourTestMethod"
```

The app runs on port 8080 locally, mapped to port 8000 in Docker.

## Architecture

**RAG Pipeline:**
1. On startup, `IngestionService` reads rules documents from `src/main/resources/docs/` and stores chunked embeddings in the vector store.
2. `ChatBotConfiguration` wires up a `ChatClient` with retrieval advisors that query the vector store per user message.
3. `ChatController` exposes `/rules` (single response) and `/rulesStream` (SSE streaming).

**Document Ingestion:**
- `DocService` interface with implementations `MarkdownDocService` (splits on `## ` headers) and `PdfDocService` (splits on numbered section pattern).
- Documents are chunked with `TokenTextSplitter` (400 token chunks, 200 char min).
- Currently ingesting: `gundam_card_game_comprehensive_rules_v1_5_0.md`

**Retrieval Advisors in `ChatBotConfiguration`:**
- `SmallComprehensiveRulesAdvisor`: top-K=16, similarity threshold=0.75 (comprehensive rules markdown)
- `WebRulesAdvisor`: top-K=2, filter `detail_level == 'general'` (web rules RTF)
- Both wrap `QuestionAnswerAdvisor` inside a custom `GundamAdvisor` for logging.

**Profiles:**
- Default: Uses PgVector (PostgreSQL must be running)
- `debug`: Uses in-memory `SimpleVectorStore` via `DebugVectorStoreConfiguration`, excludes PostgreSQL autoconfiguration

## Key Files

| File | Purpose |
|------|---------|
| `ChatController.kt` | REST endpoints (`/rules`, `/rulesStream`) |
| `ChatBotConfiguration.kt` | ChatClient bean + advisor wiring |
| `IngestionService.kt` | Startup document ingestion into vector store |
| `GundamAdvisor.kt` | Logging wrapper around `QuestionAnswerAdvisor` |
| `MarkdownDocService.kt` | Markdown document reader/splitter |
| `PdfDocService.kt` | PDF document reader/splitter |
| `application.yaml` | Main config (DB URL, OpenAI key, active profile) |
| `application-debug.yaml` | Debug profile (in-memory vector store) |
| `DebugVectorStoreConfiguration.kt` | `SimpleVectorStore` bean for debug profile |

## API

Both endpoints accept conversation history as JSON:
```json
POST /rules
POST /rulesStream

{
  "conversation": [
    {"role": "user", "content": "Can I attack directly?"},
    {"role": "assistant", "content": "..."}
  ]
}
```
`/rulesStream` returns Server-Sent Events.

## Infrastructure

- **PostgreSQL + pgvector**: `pgvector/pgvector:pg16`, DB name `gundam-tcg-rules`, credentials `user/password`
- **Schema**: Auto-initialized by Spring AI (`spring.ai.vectorstore.pgvector.initialize-schema: true`)
- **OpenAI API key**: Configured in `application.yaml` (also overridable via env var in docker-compose)
