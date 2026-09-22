# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

AIMURO is a Spring Boot (Kotlin) AI chatbot that answers Gundam Trading Card Game rules questions using an agentic RAG pipeline: a planner LLM call decides which tools (rules search, card lookup) the main model should be given, then the main model calls them itself via Spring AI tool-calling. The main tool-calling model is switchable between OpenAI and a local Ollama model via profile (see Profiles below); the planner and the embedding model are always local Ollama regardless. Backed by PostgreSQL + pgvector for semantic search.

## Build and Run Commands

```bash
# Build the project
./gradlew build

# Run locally — active profiles default to `debug,openai` in application.yaml (in-memory
# vector store + embedded H2, no Postgres/Redis needed). Needs OPEN_AI_KEY for the main model,
# AND a local Ollama on :11434 with qwen2.5:7b-instruct + qwen3-embedding:0.6b pulled — the
# planner, complexity classifier and embeddings always run on Ollama regardless of profile.
./gradlew bootRun

# Fully offline instead (no OpenAI key)
./gradlew bootRun --args='--spring.profiles.active=debug,ollama'

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

The app listens on port 8080 in both local and Docker runs. Note: `AimuroApplicationTests` is a stub with its body commented out — `MarkdownDocServiceTest` (chunking), `RulesSearchToolServiceTest` (Mockito; depth → top-K), and `RulesAgentTranscriptSessionTest` (filename sanitization) are the only real test coverage, so don't assume the orchestrator, planner or prompts are regression-tested. Prompt/tool-calling behavior can only be checked by running a request and reading the transcript (see Debugging below).

## Profiles

Profiles are combined along two independent axes:

| Axis | Profile | Effect |
|------|---------|--------|
| Infra | `debug` | In-memory `SimpleVectorStore` (`DebugVectorStoreConfiguration`), embedded H2 for both datasources (`DebugConversationJpaConfiguration`), no Redis (`StreamBufferService`/`ChatStreamProducer`/`ChatStreamConsumer`/`RedisConfiguration` are all `@Profile("!debug")` and excluded). `DebugAimuroChatServiceImpl` replaces the Redis-backed service and replays chunks from an in-memory map instead. |
| Infra | `prod` (or no infra profile) | Real Postgres for both the pgvector store and the conversation-history DB (`ConversationJpaConfiguration`, dual `DataSource`/`JdbcTemplate` beans), real Redis. |
| Main chat model | `openai` | `aimuroChatClient`, `rulesAgentChatClient`, and the currently-unused `characterChatClient` all use OpenAI `o4-mini`, needs `OPEN_AI_KEY`. Chosen over `ollama` because qwen2.5-instruct was observed missing implicit second tool-calls (e.g. not re-querying `searchRules` for a term like "Link Units" not already covered) that o4-mini catches reliably — that specific follow-up decision now happens inside `rulesAgentChatClient`'s own loop (see `RulesAgentService` below), which is why it rides this same switch rather than being pinned to Ollama like the planner/classifier. |
| Main chat model | `ollama` | `aimuroChatClient`/`rulesAgentChatClient`/`characterChatClient` use local `qwen2.5:7b-instruct` against `http://localhost:11434`, no API key. |

**The planner and the embedding model are NOT part of this switch** — they're always local Ollama regardless of which main-chat-model profile is active:
- `plannerChatClient` (used by `QueryPlannerService`'s structured-output call) is built from a manually-constructed `OllamaChatModel` bean (`plannerOllamaChatModel` in `ChatBotConfiguration.kt`) that bypasses the `spring.ai.model.chat` switch entirely — the one-shot classification call works fine on the local model, no reason to pay for OpenAI on every turn just to plan.
- Embeddings (`VectorStore`/`RulesSearchToolService` search, `IngestionService`/`MarkdownDocService` startup ingestion) are pinned via a fixed `spring.ai.model.embedding: ollama` in `application.yaml`, using `qwen3-embedding:0.6b`.
- Because of this, `spring.ai.ollama.*` connection settings (`base-url`, `chat.options.model`, `embedding.options.model`, `init.pull-model-strategy`) live in the always-active base `application.yaml`, not `application-ollama.yaml` — they're needed regardless of which main-chat-model profile is chosen, including `openai` run without also activating `ollama`. `application-ollama.yaml`/`application-openai.yaml` now only own the `spring.ai.model.chat` switch itself plus (for `openai`) the OpenAI-specific connection settings.

`application.yaml` currently defaults `spring.profiles.active` to `debug,openai` — plain `./gradlew bootRun` needs `OPEN_AI_KEY` for `aimuroChatClient` plus a local Ollama for the planner/classifier/embeddings. Pass `--spring.profiles.active=debug,ollama` for a fully offline run (no key).

## Architecture

**Agentic pipeline (planner → tool-equipped model call):**
1. `ChatController` (`POST /ask`) hands the request to `AimuroChatServiceImpl` (or `DebugAimuroChatServiceImpl` under `debug`), which kicks off async generation on a virtual thread and returns a `requestId` immediately.
2. `AgenticChatOrchestrator.streamResponse` first calls `QueryPlannerService.plan(query)`, a cheap structured-output LLM call (dedicated `@PlannerChatClient` bean, no tools attached) that returns a `QueryPlan` (`needsRulesLookup`, `needsCardLookup`, `subQuestions` — each a `{question, tool}` pair tagging which tool, if any, that sub-question needs).
3. Based on the plan, the orchestrator attaches zero, one, or both tool services (`RulesAgentService`, `CardToolService` — **not** `RulesSearchToolService` directly, see step 4) to a single `.stream()` call on the `@Primary aimuroChatClient`. It also wraps the user message as `<question>…</question><tool_routing>…</tool_routing>`, where the routing block just lists `subQuestions` grouped by tool ("Card lookups" / "Rules research"), so the model has a focused per-tool query instead of deriving one itself from a compound raw query. *How* to act on that block (one call per listed question, pass the quoted text unchanged to `findCard`/`answerRulesQuestion`, no invented keywords) is a standing instruction in the "Tool Routing Rule" section of `system-prompt.md`, not in the user message. This is advisory only — nothing in code forces the model's tool arguments, and it has been observed ignoring the planner's query in the past (e.g. calling `searchRules("Rush")`, a keyword that doesn't exist in this game). An orchestrator-side fix was tried for this — running the planner's `RULES_LOOKUP` queries procedurally instead of exposing rules research as a tool at all — and reverted: it also removed the model's ability to make a genuine follow-up rules call for a question the planner hadn't anticipated. That residual routing-fidelity risk (the model paraphrasing or skipping a routed question) is accepted as the smaller problem, especially since step 4's sub-agent already absorbs the sharper failure mode — a missed *follow-up* search — that originally motivated this whole change. The model still decides itself whether/how many times to invoke the tools it's given. If tools are attached, Spring AI 2.0's `ToolCallingAdvisor` (auto-registered in the `ChatClient` advisor chain) runs the tool-calling loop, deciding whether/how many times to invoke them and feeding results back — this may involve multiple model round-trips before the one streamed answer is produced.
4. `RulesAgentService.answerRulesQuestion(question)` is the `@Tool` the main model actually calls for rules — not a raw vector search. It runs its **own nested tool-calling loop** against a dedicated `rulesAgentChatClient` (`@RulesAgentChatClient` bean in `ChatBotConfiguration.kt`, rides the same profile-switched model as `aimuroChatClient`, system prompt `rules-agent-system-prompt.md`, with `RulesSearchToolService.searchRules` as its *only* tool) and returns one synthesized, evidence-backed `<rules_finding>` — not the raw `<passage>` blocks `searchRules` itself returns. This is where the missed-implicit-follow-up problem referenced in the Profiles table (not re-querying for "Link Units") is actually addressed: `rules-agent-system-prompt.md`'s "Follow-Up Search Rule" instructs it to notice an unresolved keyword or exception in a retrieved passage and search again before concluding — a job this sub-agent exists solely to do, rather than one of several responsibilities competing for the main model's attention alongside card lookups, grounding, and synthesis. Each call gets its own concurrency-safe transcript under `logs/rules/` (see Debugging) — deliberately not the shared `SynthesizedPromptFileWriter`, since `answerRulesQuestion` can be called more than once per request and isn't guaranteed sequential.
5. `QueryPlannerService` is fail-open: if the planning call throws or fails to parse, it falls back to a plan with both lookups enabled rather than silently skipping one the user needed.
6. Planner-prompt coupling to know about: a `RULES_LOOKUP` sub-question's text is what the main model is told to pass verbatim as `answerRulesQuestion`'s `question` argument (which the rules agent then typically uses as-is for its own first `searchRules` call), so `planner-system-prompt.md` constrains its wording (no card names, generic nouns like "unit"/"pilot", keywords kept verbatim) to match how rules chunks embed. Change that prompt and the routing block together.

**Tools (Spring AI `@Tool` methods, called by the model itself — not orchestrated procedurally):**
- `RulesAgentService.answerRulesQuestion(question)` — the rules tool `aimuroChatClient` is actually given (see Architecture step 4). Internally runs `rulesAgentChatClient`'s own tool-calling loop and returns one synthesized finding, not raw passages.
- `RulesSearchToolService.searchRules(query)` — semantic search against the pgvector store. **Not attached to `aimuroChatClient`** — its only caller is `rulesAgentChatClient`, wired as that client's sole `.defaultTools(...)` entry in `ChatBotConfiguration.kt`. The service determines question complexity itself: `RulesComplexityClassifier` runs a one-shot structured-output call (dedicated `@ComplexityChatClient` bean, always local Ollama like the planner, prompt in `prompts/rules-complexity-prompt.md`) returning a `SearchDepth` (`SIMPLE`/`MODERATE`/`IN_DEPTH`) that maps to top-K 10/16/20, failing open to `MODERATE`. Neither the planner nor the rules agent supplies depth — the planner has no `depth` field since it only applies to rules search. The rules agent is instructed to write a focused search query rather than pass the raw sub-question unmodified; the classifier sees that query.
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
- Externalized as Markdown under `src/main/resources/prompts/` (`system-prompt.md`, `planner-system-prompt.md`, `rules-complexity-prompt.md`, `character-prompt.md`, `rules-agent-system-prompt.md`), loaded lazily via `PromptConfig`/`DefaultPromptConfig` and wired as `.defaultSystem(...)` on the respective `ChatClient` beans in `ChatBotConfiguration`. Anything per-request (the `<tool_routing>` question lists) has to go in the user message, since `defaultSystem` is fixed per client. Edit the `.md` files directly; no recompile needed for prompt-only changes (still need a restart).
- `rules-agent-system-prompt.md` is `rulesAgentChatClient`'s system prompt — deliberately narrower than `system-prompt.md` (only `searchRules`, no card lookups, no cross-sub-question synthesis) plus one thing `system-prompt.md` doesn't need: a "Follow-Up Search Rule" instructing it to notice an unresolved keyword/exception in a retrieved passage and search again before concluding, and an "Output Format" section requiring an *extractive* `<rules_finding>` (general rule and exception stated together, never one without the other) rather than a loose paraphrase that could quietly drop the exception.

## Debugging Tool-Calling / Prompts

Since Spring AI 2.0 the tool loop is `ToolCallingAdvisor` inside the advisor chain, so an advisor ordered after it (`order > ToolCallingAdvisor.DEFAULT_ORDER`) runs once per round-trip. `RoundTripLoggingAdvisor` (registered on `aimuroChatClient` only) uses that to log every main-chat round-trip's own output — text and/or `toolCall name=… arguments=…` — plus its `finishReason` and token usage (`n/a` when the provider reports none). Under `debug`, `SynthesizedPromptFileWriter` additionally writes a copy/paste-friendly transcript per `/ask` request to `logs/synthesized-prompt-<yyyyMMdd-HHmmss-SSS>.txt` (system prompt, user message, then per round: new messages, the round's response with `finishReason`/usage, tool responses; final streamed answer last). Override the base path with `app.llm-prompt-log.path`. `logs/` is git-ignored and grows one file per request. This is the fastest way to see what arguments the model actually passed to a tool versus what the planner suggested.

`finishReason` is provider-native and not normalized: OpenAI reports `tool_calls` for a tool-calling round, but local Ollama reports `stop` even when the round is a tool call — so decide "was this a tool round?" from the presence of `toolCall` lines, not from `finishReason`.

`ObservabilityConfiguration`'s `ChatModelIoLoggingHandler` (a Micrometer observation handler) still logs each model call's prompt, but for the main chat it deliberately omits the response: under streaming, its `context.response` is aliased to the final aggregated response on every round but the last. Note it only fires for chat models built with the app's `ObservationRegistry` — the manually-built `plannerOllamaChatModel` (planner + complexity classifier) does not set one, so those calls are not logged by it.

`RulesAgentService.answerRulesQuestion` gets its own, separate transcript mechanism rather than reusing `SynthesizedPromptFileWriter`: one file per call under `logs/rules/`, named from the sanitized question text plus a timestamp (`RulesAgentTranscriptSession`, created fresh per call — a plain object, not a singleton bean, so nothing races if the main model calls `answerRulesQuestion` more than once in a request). `RulesAgentRoundTripLoggingAdvisor` (built fresh per call by `RulesAgentRoundTripLoggingAdvisorFactory` and attached via request-level `.advisors(...)`, never `.defaultAdvisors(...)`) logs that call's own internal `searchRules` round-trips into it, reusing `RoundTripLoggingAdvisor.kt`'s `renderRound`/`renderMessage` (module-visible `internal` functions) with `label = "rules-agent"`. Gated to `debug` like the main writer.

## Key Files

| File | Purpose |
|------|---------|
| `ChatController.kt` | REST endpoints (`/ask`, `/ask/{requestId}/stream`, `/conversation/{id}`) |
| `ConversationController.kt` | `POST /conversation` — creates a new conversation row, returns its id |
| `AgenticChatOrchestrator.kt` | Plan-then-call sequence shared by prod and debug chat services |
| `AimuroChatServiceImpl.kt` | Prod (`!debug`) ask/replay orchestration via Redis, async generation |
| `DebugAimuroChatServiceImpl.kt` | Debug-profile ask/replay orchestration via in-memory maps |
| `QueryPlannerService.kt` / `QueryPlan.kt` | Structured-output planner call deciding which tools to offer |
| `RulesAgentService.kt` | `@Tool answerRulesQuestion` — the rules tool `aimuroChatClient` actually has; runs its own nested loop against `rulesAgentChatClient` |
| `RulesAgentTranscriptSession.kt` / `RulesAgentRoundTripLoggingAdvisor.kt` / `RulesAgentRoundTripLoggingAdvisorFactory.kt` | Per-call (not singleton) transcript logging for `answerRulesQuestion`'s internal loop → `logs/rules/` (see Debugging) |
| `RulesSearchToolService.kt` | `@Tool` rules vector search, depth-scaled top-K — only attached to `rulesAgentChatClient`, not `aimuroChatClient` |
| `RoundTripLoggingAdvisor.kt` | Advisor inside the `ToolCallingAdvisor` loop: per-round-trip output, `finishReason`, usage → log + transcript (see Debugging); also supplies `renderRound`/`renderMessage`, reused by `RulesAgentRoundTripLoggingAdvisor` |
| `ObservabilityConfiguration.kt` / `SynthesizedPromptFileWriter.kt` | Micrometer prompt logging + per-request (main-chat only) transcript file writer (see Debugging) |
| `RulesComplexityClassifier.kt` | Local-Ollama classifier deciding `SearchDepth` (and owning the enum) for a rules query |
| `CardToolService.kt` | `@Tool` card lookups, delegates to `GundamCardService` |
| `GundamCardGraphQlClient.kt` / `GundamCardService.kt` | GraphQL client to `gundamhub-card-service` |
| `ChatBotConfiguration.kt` | `aimuroChatClient` (`@Primary`, no default tools) + `rulesAgentChatClient` (`searchRules` as its one default tool) + `plannerChatClient` beans |
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
