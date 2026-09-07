# AiMURO
<div>
<img align="left" src="https://github.com/user-attachments/assets/1f428f2b-fa3f-4534-bc7f-d2935fd12b34" alt="aimuro" width="200">
<h3>AI-powered rules assistant for the Gundam Trading Card Game.</h3>

AiMURO answers natural-language rules questions with the accuracy of a tournament judge and the personality of a friendly anime robot. It combines semantic search over official rules documents with real-time card data retrieval, grounding every answer in authoritative source material.

</div>

<br></br>

# Technical Highlights

### Agentic RAG via Planner + Tool-Calling

Rather than a fixed retrieval pipeline, AiMURO decides per-request what information it actually needs and lets the model fetch it itself:

1. **Query Planning** — `QueryPlannerService` makes a cheap, tool-free LLM call that decomposes the question into sub-questions, tags each one with the tool it needs (card lookup, rules search, or neither), and assesses how deep a rules search should go (`SIMPLE` / `MODERATE` / `IN_DEPTH`). It's fail-open: if planning fails or doesn't parse, it defaults to offering both tools rather than silently skipping one the user needed.
2. **Conditional Tool Attachment** — `AgenticChatOrchestrator` attaches only the tools the plan calls for (`RulesSearchToolService`, `CardToolService`, both, or neither) to a single call on the main chat model, along with a routing-hint block giving the model a focused, self-contained query per sub-question instead of making it derive one from the raw compound message.
3. **Model-Driven Retrieval** — the model itself decides whether, how many times, and in what order to invoke the tools it's given, via Spring AI's built-in tool-calling loop. A single streamed answer can involve multiple tool round-trips under the hood before the model produces its final response.

### Discriminative Embedding Strategy

Rules documents are split on a `##` > `####` > `#####` heading hierarchy — each `#####` leaf (e.g. a keyword effect like `<Suppression>`) becomes its own embedded chunk, with no further token-splitting. A few decisions keep embeddings semantically sharp:

- **No breadcrumb prefixes** — prepending the full ancestor path (e.g. `13) Keyword Effects > 13-1. Keyword Effects > 13-1-7. <Suppression>`) to every chunk pulls sibling chunks together in embedding space and degrades search precision. Chunks carry only their own heading text, with section numbers stripped so the concept term dominates the embedding.
- **Title-only sections are embedded, not dropped** — most atomic rules are numbered as a single heading line with no body text below them (e.g. "a newly deployed Unit cannot attack on the turn it is deployed"). These use the cleaned heading text itself as the chunk content.
- **Stated exceptions get an extra standalone chunk** — a sub-rule that states a qualifier or exception to a general rule (e.g. "Link Units can attack the turn they're deployed") is folded into its parent chunk *and* embedded separately, so it stays independently searchable instead of getting diluted inside a larger merged chunk of unrelated sibling text.

### Live Card Data via GraphQL

Card lookups (`CardToolService.findCard(name)` / `findCards(filter)`) hit a live GraphQL API (`GundamCardGraphQlClient`) rather than a static snapshot, so card text and attributes always reflect the current card database.

### Externalized Prompts

All prompt text lives in `src/main/resources/prompts/*.md` (`system-prompt.md`, `planner-system-prompt.md`, `character-prompt.md`) and loads lazily at startup, so prompt wording can be edited without recompiling — a restart is still needed to pick up the change.

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
| Model | `openai` / `ollama` | `openai`: `o4-mini` chat + `text-embedding-3-small` embeddings, needs `OPEN_AI_KEY`. `ollama`: `llama3.1` chat + `qwen3-embedding:0.6b` embeddings, fully local, no API key. |

`application.yaml` defaults to `debug,ollama` — plain `./gradlew bootRun` needs nothing but a local Ollama running. Docker Compose brings up the full stack — app + pgvector + PostgreSQL + Redis — with a single command.

---

## Stack

| Layer | Technology |
|-------|-----------|
| Runtime | Kotlin / Spring Boot |
| AI Framework | Spring AI |
| LLM | OpenAI `o4-mini` (`openai` profile) / Ollama `llama3.1` (`ollama` profile) |
| Embeddings | OpenAI `text-embedding-3-small` / Ollama `qwen3-embedding:0.6b` |
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
  └─ QueryPlannerService.plan(query) — decomposes into sub-questions,
     tags each with a tool (card lookup / rules search / none), sets depth
  └─ Attaches only the tools the plan calls for: RulesSearchToolService,
     CardToolService, both, or neither
  └─ Single .stream() call on the main chat model, with a per-tool
     routing-hint block appended to the user message
      │
      ▼
Main LLM (OpenAI o4-mini or Ollama llama3.1)
  └─ Spring AI tool-calling loop — the model itself decides whether,
     how many times, and in what order to call:
       ├─ searchRules(query, depth) → similarity search against pgvector
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

# Full stack with real infra + Ollama (requires Ollama running locally on port 11434)
./gradlew bootRun --args='--spring.profiles.active=prod,ollama'

# Debug mode — no database or Redis required (this is the default: plain `./gradlew bootRun` works too)
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
