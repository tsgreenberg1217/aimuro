# AiMURO
<div>
<img align="left" src="https://github.com/user-attachments/assets/1f428f2b-fa3f-4534-bc7f-d2935fd12b34" alt="aimuro" width="200">
<h3>AI-powered rules assistant for the Gundam Trading Card Game.</h3>

AiMURO answers natural-language rules questions with the accuracy of a tournament judge and the personality of a friendly anime robot. It combines semantic search over official rules documents with real-time card data retrieval, grounding every answer in authoritative source material.

</div>

<br></br>

# Technical Highlights

### Agentic RAG via Planner + Sub-Agent Tool-Calling

Rather than a fixed retrieval pipeline, AiMURO decides per-request what information it actually needs and lets the model fetch it itself — with a dedicated rules sub-agent handling the "did I actually resolve this" follow-up work:

1. **Query Planning** — `QueryPlannerService` makes a cheap, tool-free LLM call (always local Ollama) that decomposes the question into sub-questions and tags each one with the tool it needs (card lookup, rules search, or neither). It's fail-open: if planning fails or doesn't parse, it defaults to offering both tools rather than silently skipping one the user needed.
2. **Conditional Tool Attachment** — `AgenticChatOrchestrator` attaches only the tools the plan calls for (`RulesAgentService`, `CardToolService`, both, or neither) to a single call on the main chat model, along with a routing-hint block giving the model a focused, self-contained query per sub-question instead of making it derive one from the raw compound message.
3. **Model-Driven Retrieval** — the model itself decides whether, how many times, and in what order to invoke the tools it's given, via Spring AI's built-in tool-calling loop. A single streamed answer can involve multiple tool round-trips under the hood before the model produces its final response.
4. **Rules Sub-Agent** — for rules questions, the main model doesn't call vector search directly. It calls `RulesAgentService.answerRulesQuestion(question)`, which runs its *own* nested tool-calling loop (a separate chat client whose only tool is `RulesSearchToolService.searchRules`) and returns one synthesized, evidence-backed finding. That sub-agent is instructed to notice an unresolved keyword or exception in a retrieved passage and search again before concluding — a job that competes for attention when it's just one of several responsibilities on the main model, but gets full focus here. Search depth (`SIMPLE` / `MODERATE` / `IN_DEPTH`, mapping to top-K 10/16/20) is decided by a one-shot local-Ollama classifier (`RulesComplexityClassifier`) run against the sub-agent's own search query, not by the planner.

### Discriminative Embedding Strategy

Rules documents are split on a `##` > `####` > `#####` heading hierarchy — each `#####` leaf (e.g. a keyword effect like `<Suppression>`) becomes its own embedded chunk, with no further token-splitting. A few decisions keep embeddings semantically sharp:

- **No breadcrumb prefixes** — prepending the full ancestor path (e.g. `13) Keyword Effects > 13-1. Keyword Effects > 13-1-7. <Suppression>`) to every chunk pulls sibling chunks together in embedding space and degrades search precision. Chunks carry only their own heading text, with section numbers stripped so the concept term dominates the embedding.
- **Title-only sections are embedded, not dropped** — most atomic rules are numbered as a single heading line with no body text below them (e.g. "a newly deployed Unit cannot attack on the turn it is deployed"). These use the cleaned heading text itself as the chunk content.
- **Stated exceptions get an extra standalone chunk** — a sub-rule that states a qualifier or exception to a general rule (e.g. "Link Units can attack the turn they're deployed") is folded into its parent chunk *and* embedded separately, so it stays independently searchable instead of getting diluted inside a larger merged chunk of unrelated sibling text.

### Live Card Data via GraphQL

Card lookups (`CardToolService.findCard(name)` / `findCards(filter)`) hit a live GraphQL API (`GundamCardGraphQlClient`) rather than a static snapshot, so card text and attributes always reflect the current card database.

### Externalized Prompts

All prompt text lives in `src/main/resources/prompts/*.md` (`system-prompt.md`, `planner-system-prompt.md`, `rules-agent-system-prompt.md`, `rules-complexity-prompt.md`, `character-prompt.md`) and loads lazily at startup, so prompt wording can be edited without recompiling — a restart is still needed to pick up the change.

### Round-Trip Transcript Logging

Every tool-calling round-trip — main chat and the rules sub-agent's own internal loop alike — is logged for debugging prompt/tool behavior. Under the `debug` profile, each `/ask` request gets a full copy/paste-friendly transcript (system prompt, user message, every round's model output and tool responses, final answer) written to `logs/`, and each `answerRulesQuestion` call gets its own separate transcript under `logs/rules/` since it can be invoked more than once per request. This is the fastest way to see what arguments the model actually passed to a tool versus what the planner suggested.

### Resilient SSE Streaming

Rather than piping the AI response directly to an SSE connection, AIMURO decouples generation from delivery using Redis Streams:

1. `POST /ask` kicks off generation on a virtual thread and immediately returns a request ID; the client opens an SSE connection backed by a Redis stream.
2. Each response chunk is published to `stream:{requestId}` as it arrives. A `done=true` sentinel closes the consumer.
3. If the client disconnects mid-stream, it can reconnect via `GET /ask/{requestId}/stream` — the stream picks up from Redis, and a 10-minute TTL on completed streams means late fetches still work.

Under the `debug` profile, the same ask/replay contract runs against an in-process map instead of Redis, so this all works with no infrastructure running locally.

### Production-Ready Infrastructure, Debug-Friendly Development

Profiles combine along two independent axes:

| Axis | Options | Effect |
|------|---------|--------|
| Infra | `debug` / `prod` | `debug`: in-memory vector store + embedded H2, no Redis. `prod` (default when no infra profile is set): real Postgres for both pgvector and conversation history, real Redis. |
| Main chat model | `openai` / `ollama` | Switches only the main chat client, the rules sub-agent's client, and the (currently unused) character client. `openai`: `o4-mini`, needs `OPEN_AI_KEY` — chosen because it was more reliable than `qwen2.5:7b-instruct` at making genuine follow-up tool calls. `ollama`: local `qwen2.5:7b-instruct` against `http://localhost:11434`, no API key. |

The query planner, the rules-search complexity classifier, and embeddings (`qwen3-embedding:0.6b`) are **not** part of that switch — they always run on local Ollama regardless of which main-chat-model profile is active, since there's no reason to pay for OpenAI on a one-shot classification or on embedding generation.

`application.yaml` defaults to `debug,openai` — plain `./gradlew bootRun` needs `OPEN_AI_KEY` plus a local Ollama running (for the planner, classifier, and embeddings). Pass `--spring.profiles.active=debug,ollama` for a fully offline run with no key. Docker Compose brings up the full stack — app + pgvector + PostgreSQL + Redis — with a single command.

---

## Stack

| Layer | Technology |
|-------|-----------|
| Runtime | Kotlin / Spring Boot |
| AI Framework | Spring AI |
| Main Chat LLM | OpenAI `o4-mini` (`openai` profile) / Ollama `qwen2.5:7b-instruct` (`ollama` profile) |
| Planner / Classifier LLM | Ollama `qwen2.5:7b-instruct` (always, regardless of main-chat profile) |
| Embeddings | Ollama `qwen3-embedding:0.6b` (always, regardless of main-chat profile) |
| Vector Store | PostgreSQL + pgvector (pg18) |
| Conversation History | PostgreSQL (JPA) |
| Stream Buffer | Redis Streams |
| Card Data | GraphQL API |
| Containerization | Docker / Docker Compose |

---

## Architecture

```
POST /ask  (conversationId + conversation history)
      │
      ▼
AimuroChatServiceImpl (DebugAimuroChatServiceImpl under `debug`)
  └─ Spawns virtual thread for async generation, returns requestId immediately
  └─ Client opens SSE via GET /ask/{requestId}/stream
      │
      ▼
AgenticChatOrchestrator.streamResponse
  └─ QueryPlannerService.plan(query) [local Ollama] — decomposes into
     sub-questions, tags each with a tool (card lookup / rules search / none)
  └─ Attaches only the tools the plan calls for: RulesAgentService,
     CardToolService, both, or neither
  └─ Single .stream() call on the main chat model, with a per-tool
     routing-hint block appended to the user message
      │
      ▼
Main LLM (OpenAI o4-mini or Ollama qwen2.5:7b-instruct)
  └─ Spring AI tool-calling loop — the model itself decides whether,
     how many times, and in what order to call:
       ├─ answerRulesQuestion(question) → RulesAgentService
       │     └─ its OWN nested tool-calling loop, sole tool = searchRules
       │           ├─ RulesComplexityClassifier [local Ollama] picks
       │           │  SIMPLE/MODERATE/IN_DEPTH → top-K 10/16/20
       │           └─ similarity search against pgvector
       │     └─ notices unresolved keywords/exceptions and re-searches
       │        before returning one synthesized <rules_finding>
       └─ findCard(name) / findCards(filter) → GraphQL → gundamhub-card-service
  └─ Produces one streamed final answer once all tool round-trips resolve
      │
      ▼
ChatStreamProducer → Redis stream:{requestId} (in-memory map under `debug`)
  └─ On complete: saves to PostgreSQL, writes done sentinel, sets 10-min TTL
      │
      ▼
ChatStreamConsumer
  └─ Reads the stream, terminates on the done sentinel
  └─ Emits final isComplete=true event to client

GET /ask/{requestId}/stream  → Reconnect to / replay an in-progress or completed stream
```

---

## Getting Started

```bash
# Full stack with OpenAI (Docker)
OPEN_AI_KEY=your-key ./aimuro-build.sh

# Full stack with real infra + Ollama (requires Ollama running locally on port 11434
# with qwen2.5:7b-instruct + qwen3-embedding:0.6b pulled)
./gradlew bootRun --args='--spring.profiles.active=prod,ollama'

# Debug mode — no database or Redis required (this is the default: plain `./gradlew bootRun`
# works too, but still needs OPEN_AI_KEY plus a local Ollama for the planner/classifier/embeddings)
./gradlew bootRun --args='--spring.profiles.active=debug,openai'

# Fully offline debug mode — no OpenAI key needed at all
./gradlew bootRun --args='--spring.profiles.active=debug,ollama'
```

App runs on `localhost:8080` in both local and Docker.

---

## API

```bash
# Start a new question — returns SSE immediately
POST /ask
{
  "conversationId": 1,
  "conversation": [
    { "role": "user",      "content": "Can my Gundam attack the turn it's played?" },
    { "role": "assistant", "content": "..." }
  ]
}

# Reconnect to an in-progress or completed stream
GET /ask/{requestId}/stream

# Get full conversation history
GET /conversation/{conversationId}

# Check stream status for a conversation
GET /conversation/{conversationId}/status
```

Both `/ask` and `/ask/{requestId}/stream` return `text/event-stream`. Each event is a `RulesResponse` with `answer` (chunk) and `isComplete` (`true` on the final event).

## K8
You can also use the gundamhub-k8 to run this as part of a cluster. Set `OPEN_AI_KEY` in your environment as your key from Open AI.
