# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

AIMURO is a Spring Boot (Kotlin) AI chatbot that answers Gundam Trading Card Game rules questions using an agentic RAG pipeline: a planner LLM call decides which tools (rules search, card lookup) the main model should be given, then the main model calls them itself via Spring AI tool-calling. The main tool-calling model is switchable between OpenAI and a local Ollama model via profile (see Profiles below); the planner and the embedding model are always local Ollama regardless. Backed by PostgreSQL + pgvector for semantic search.

## Build and Run Commands

```bash
# Build the project
./gradlew build

# Run locally — active profiles default to `debug,ollama` in application.yaml (in-memory
# vector store + embedded H2, no Postgres/Redis needed; talks to a local Ollama on :11434)
./gradlew bootRun

# Run against real infra instead (Postgres + pgvector + Redis via the monorepo docker-compose)
./gradlew bootRun --args='--spring.profiles.active=prod,openai' # requires OPEN_AI_KEY
./gradlew bootRun --args='--spring.profiles.active=prod,ollama'

# Build Docker image (see gundamhub/gundam-hub-build.sh / aimuro-build.sh in the parent repo
# for the full-stack docker-compose flow)
./aimuro-build.sh
# or manually:
docker build -t aimuro-service .

# Run tests
./gradlew test

# Run a single test class
./gradlew test --tests "com.aimuro.etl.document_service.MarkdownDocServiceTest"
```

The app listens on port 8080 in both local and Docker runs. Note: `AimuroApplicationTests` is a stub with its body commented out — `MarkdownDocServiceTest` is the only real test coverage in the repo, so don't assume behavior elsewhere is regression-tested.

## Profiles

Profiles are combined along two independent axes:

| Axis | Profile | Effect |
|------|---------|--------|
| Infra | `debug` | In-memory `SimpleVectorStore` (`DebugVectorStoreConfiguration`), embedded H2 for both datasources (`DebugConversationJpaConfiguration`), no Redis (`StreamBufferService`/`ChatStreamProducer`/`ChatStreamConsumer`/`RedisConfiguration` are all `@Profile("!debug")` and excluded). `DebugAimuroChatServiceImpl` replaces the Redis-backed service and replays chunks from an in-memory map instead. |
| Infra | `prod` (or no infra profile) | Real Postgres for both the pgvector store and the conversation-history DB (`ConversationJpaConfiguration`, dual `DataSource`/`JdbcTemplate` beans), real Redis. |
| Main chat model | `openai` | `aimuroChatClient` (and the currently-unused `characterChatClient`) use OpenAI `o4-mini`, needs `OPEN_AI_KEY`. Chosen over `ollama` for the main tool-calling loop because qwen2.5-instruct was observed missing implicit second tool-calls (e.g. not re-querying `searchRules` for a term like "Link Units" not already covered) that o4-mini catches reliably. |
| Main chat model | `ollama` (default) | `aimuroChatClient`/`characterChatClient` use local `qwen2.5:7b-instruct` against `http://localhost:11434`, no API key. |

**The planner and the embedding model are NOT part of this switch** — they're always local Ollama regardless of which main-chat-model profile is active:
- `plannerChatClient` (used by `QueryPlannerService`'s structured-output call) is built from a manually-constructed `OllamaChatModel` bean (`plannerOllamaChatModel` in `ChatBotConfiguration.kt`) that bypasses the `spring.ai.model.chat` switch entirely — the one-shot classification call works fine on the local model, no reason to pay for OpenAI on every turn just to plan.
- Embeddings (`VectorStore`/`RulesSearchToolService` search, `IngestionService`/`MarkdownDocService` startup ingestion) are pinned via a fixed `spring.ai.model.embedding: ollama` in `application.yaml`, using `qwen3-embedding:0.6b`.
- Because of this, `spring.ai.ollama.*` connection settings (`base-url`, `chat.options.model`, `embedding.options.model`, `init.pull-model-strategy`) live in the always-active base `application.yaml`, not `application-ollama.yaml` — they're needed regardless of which main-chat-model profile is chosen, including `openai` run without also activating `ollama`. `application-ollama.yaml`/`application-openai.yaml` now only own the `spring.ai.model.chat` switch itself plus (for `openai`) the OpenAI-specific connection settings.

`application.yaml` currently defaults `spring.profiles.active` to `debug,ollama` — plain `./gradlew bootRun` needs nothing but a local Ollama running (`qwen2.5:7b-instruct` + `qwen3-embedding:0.6b` pulled), fully offline. Add `openai` (e.g. `--spring.profiles.active=debug,ollama,openai`) to switch `aimuroChatClient` onto OpenAI — `OPEN_AI_KEY` is then required, but the planner and embeddings still don't need it.

## Architecture

**Agentic pipeline (planner → tool-equipped model call):**
1. `ChatController` (`POST /ask`) hands the request to `AimuroChatServiceImpl` (or `DebugAimuroChatServiceImpl` under `debug`), which kicks off async generation on a virtual thread and returns a `requestId` immediately.
2. `AgenticChatOrchestrator.streamResponse` first calls `QueryPlannerService.plan(query)`, a cheap structured-output LLM call (dedicated `@PlannerChatClient` bean, no tools attached) that returns a `QueryPlan` (`needsRulesLookup`, `needsCardLookup`, `depth`, `subQuestions` — each a `{question, tool}` pair tagging which tool, if any, that sub-question needs).
3. Based on the plan, the orchestrator attaches zero, one, or both tool services (`RulesSearchToolService`, `CardToolService`) to a single `.stream()` call on the `@Primary aimuroChatClient`. It also appends a routing-hint block built from `subQuestions` (grouped by tool) to the user message, so the model has a focused per-tool query instead of deriving one itself from a compound raw query — the model still decides itself whether/how many times to invoke the tools it's given. If tools are attached, Spring AI's internal tool-calling loop decides whether/how many times to invoke them and feeds results back — this may involve multiple model round-trips before the one streamed answer is produced.
4. `QueryPlannerService` is fail-open: if the planning call throws or fails to parse, it falls back to a plan with both lookups enabled rather than silently skipping one the user needed.

**Tools (Spring AI `@Tool` methods, called by the model itself — not orchestrated procedurally):**
- `RulesSearchToolService.searchRules(query, depth)` — semantic search against the pgvector store. `depth` (`SIMPLE`/`MODERATE`/`IN_DEPTH`) maps to top-K 10/16/20; the model is instructed to write a focused search query rather than pass the raw user message. Note: `QueryPlan.depth` is computed by `QueryPlannerService` but never threaded into this call — `AgenticChatOrchestrator.buildToolHints` only forwards the per-tool sub-questions, not `depth` — so the main model picks its own `depth` argument independently of what the planner assessed.
- `CardToolService.findCard(name)` / `findCards(filter: CardFilterQuery)` — live card lookups via `GundamCardGraphQlClient`, which calls the `gundamhub-card-service` GraphQL API (`gundam.card.service.url`, default `http://localhost:8082/graphql`; GraphQL documents in `src/main/resources/graphql-documents/`).

**SSE Resilience (ask/replay pattern, `!debug` only):**
- `ChatStreamProducer` writes AI response chunks to a Redis stream (`stream:{requestId}`). A sentinel `done=true` message signals completion.
- `ChatStreamConsumer` reads from the Redis stream with `takeWhile { done != "true" }`, terminating the SSE flux when the sentinel arrives.
- `GET /ask/{requestId}/stream` allows clients to reconnect mid-stream or replay a completed response.
- `StreamBufferService` tracks request state (`in_progress` / `complete` / `error`) in Redis with a 10-minute TTL applied after completion.
- Under `debug`, `DebugAimuroChatServiceImpl` reimplements the same ask/replay contract with an in-process `ConcurrentHashMap` instead of Redis.

**Document Ingestion (`IngestionService`, `@Component("debug")` — the string is just a bean name, there is no `@Profile` guard, so it runs as a `CommandLineRunner` on every startup regardless of active profile):**
- `MarkdownDocService` splits `gundam_card_game_comprehensive_rules_v1_5_0.md` on a `##` > `####` > `#####` heading hierarchy; each `#####` leaf (e.g. a keyword effect like `<Suppression>`) becomes its own `Document` with no further token-splitting. A leaf with no body text after its heading (common — the source doc numbers most atomic rules as a single heading line, e.g. `##### 3-2-4. Unless specified otherwise, a newly deployed Unit cannot attack...`) still produces a `Document`, using the cleaned heading text itself as the content — `flushSection` must never silently drop a title-only section, since that previously discarded ~80% of the rulebook.
- `######`-depth lines (the deepest heading level the doc uses) are folded into their parent `#####` chunk, *and* separately emitted as their own standalone chunk when the line states a qualifier/exception (matches `EXCEPTION_PATTERN` — "unless", "normally", "except", etc.) rather than an unrelated fact. This targeted duplication exists because a fact stated as an exception to a general rule (e.g. "Link Units can attack the turn they're deployed") gets embedding-diluted into irrelevance when it only lives inside a large merged parent chunk — see the comments at the top of `MarkdownDocService.getDocs()` before changing either behavior.
- Deliberately no breadcrumb/ancestor-path prefix on chunk text (sibling chunks would cluster in embedding space) and section numbers are stripped from titles/body so the concept term dominates the embedding.
- Re-ingests unconditionally on every boot; there's no dedup/upsert check against existing vector store contents.

**Prompts:**
- Externalized as Markdown under `src/main/resources/prompts/` (`system-prompt.md`, `planner-system-prompt.md`, `character-prompt.md`), loaded lazily via `PromptConfig`/`DefaultPromptConfig` and wired as `.defaultSystem(...)` on the respective `ChatClient` beans in `ChatBotConfiguration`. Edit the `.md` files directly; no recompile needed for prompt-only changes (still need a restart).

## Key Files

| File | Purpose |
|------|---------|
| `ChatController.kt` | REST endpoints (`/ask`, `/ask/{requestId}/stream`, `/conversation/{id}`) |
| `ConversationController.kt` | `POST /conversation` — creates a new conversation row, returns its id |
| `AgenticChatOrchestrator.kt` | Plan-then-call sequence shared by prod and debug chat services |
| `AimuroChatServiceImpl.kt` | Prod (`!debug`) ask/replay orchestration via Redis, async generation |
| `DebugAimuroChatServiceImpl.kt` | Debug-profile ask/replay orchestration via in-memory maps |
| `QueryPlannerService.kt` / `QueryPlan.kt` | Structured-output planner call deciding which tools to offer |
| `RulesSearchToolService.kt` | `@Tool` rules vector search, depth-scaled top-K |
| `CardToolService.kt` | `@Tool` card lookups, delegates to `GundamCardService` |
| `GundamCardGraphQlClient.kt` / `GundamCardService.kt` | GraphQL client to `gundamhub-card-service` |
| `ChatBotConfiguration.kt` | `aimuroChatClient` (`@Primary`, no default tools) + `plannerChatClient` beans |
| `PromptConfig.kt` / `DefaultPromptConfig.kt` | Loads prompt text from `resources/prompts/*.md` |
| `IngestionService.kt` | Startup document ingestion into vector store (runs every boot, any profile) |
| `MarkdownDocService.kt` | Markdown heading-based document splitter (see chunking notes above) |
| `ChatStreamProducer.kt` / `ChatStreamConsumer.kt` | Redis stream chunk writer / SSE reader (`!debug`) |
| `StreamBufferService.kt` | Request status + TTL tracking in Redis (`!debug`) |
| `RedisConfiguration.kt` | `StreamReceiver` bean (100ms poll timeout, `!debug`) |
| `ConversationJpaConfiguration.kt` | Dual datasource config: pgvector DB (`@Primary`) + conversation DB (`!debug`) |
| `DebugConversationJpaConfiguration.kt` / `DebugVectorStoreConfiguration.kt` | Embedded H2 / in-memory vector store for `debug` |
| `GundamCardClientConfiguration.kt` | `HttpSyncGraphQlClient` bean, base URL from `gundam.card.service.url` |
| `application.yaml` / `application-{debug,prod,openai,ollama}.yaml` | Profile-specific config (see Profiles table above) |

## API

All endpoints require a `conversationId`. `/ask` starts streaming immediately; `/ask/{requestId}/stream` reconnects to an in-progress or completed stream.

```json
POST /ask
GET  /ask/{requestId}/stream
GET  /conversation/{conversationId}
GET  /conversation/{conversationId}/status
POST /conversation

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

- **pgvector DB**: `pgvector:5432`, DB name `gundam-tcg-rules-vector-db`, user `postgres` (real infra / `prod` profile only)
- **Conversation DB**: `postgres:5432`, DB name `aimuro-conversation-db`, user `postgres` (real infra / `prod` profile only)
- **Redis**: `localhost:6379` — response stream buffering and request state (`prod` / `!debug` only)
- **Card service**: `gundam.card.service.url`, default `http://localhost:8082/graphql` — see `gundamhub-card-service` in the parent monorepo
- **Docker port**: app maps `8080:8080`
- **OpenAI API key**: `OPEN_AI_KEY` env var, required only under the `openai` model profile
