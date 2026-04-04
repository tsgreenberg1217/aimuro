# AIMURO

**AI-powered rules assistant for the Gundam Trading Card Game.**

AIMURO answers natural-language rules questions with the accuracy of a tournament judge and the personality of a friendly anime robot. It combines semantic search over official rules documents with real-time card data retrieval — grounding every answer in authoritative source material rather than hallucinated training data.

---

## Technical Highlights

### Multi-Stage Retrieval-Augmented Generation (RAG)

The answer pipeline runs through two coordinated advisors before the model ever generates a response:

1. **Card Enrichment (pre-flight LLM call)** — A dedicated `ChatClient` with tool-calling enabled analyzes the user's question and, if a specific card is mentioned, fetches live card data via GraphQL before the main query executes. The enriched card attributes (type, level, cost, color, traits, effects) are injected into the prompt so retrieval is card-aware.

2. **Adaptive Vector Search** — A custom `GundamAdvisor` wrapping Spring AI's `QuestionAnswerAdvisor` first classifies question complexity (SIMPLE / MODERATE / IN_DEPTH) with a lightweight LLM call, then dynamically adjusts top-K retrieval (6 / 10 / 16 chunks) against a PostgreSQL + pgvector store. Simple lookups stay lean; complex multi-rule interactions pull broader context.

The result: retrieval depth scales with question complexity automatically, without any user-side configuration.

### Agentic Tool Calling

The card enrichment stage uses Spring AI's `@Tool` annotation to expose two callable functions to the LLM:

- `findCard(name)` — exact card lookup by name
- `findCards(filter)` — filtered search by color, level, cost, and unit trait

The model decides autonomously whether card data is needed and which tool to invoke — a lightweight agentic loop running as a preprocessing step before the main response.

### Live Card Data via GraphQL

Card lookups hit a live GraphQL API (`GundamCardGraphQlClient`) rather than a static snapshot, ensuring card text and attributes always reflect the current card database.

### Resilient SSE Streaming

Rather than piping the AI response directly to an SSE connection, AIMURO decouples generation from delivery using Redis Streams:

1. `POST /ask` kicks off generation on a virtual thread and immediately opens an SSE connection backed by a Redis stream.
2. Each response chunk is published to `stream:{requestId}` as it arrives. A `done=true` sentinel closes the consumer.
3. If the client disconnects mid-stream, it can reconnect via `GET /ask/{requestId}/stream` — the stream is replayed from the beginning, and the 10-minute TTL on completed streams means late fetches still work.

This means a dropped connection never loses a response.

### Production-Ready Infrastructure, Debug-Friendly Development

| Mode | Vector Store | Conversation DB | Redis |
|------|-------------|-----------------|-------|
| `prod` (Docker) | PgVector (pgvector pg18) | PostgreSQL `aimuro-app` | Required |
| Default (local) | PgVector | PostgreSQL | Required |
| `debug` profile | In-memory `SimpleVectorStore` | None required | Not used |

Spring profiles let engineers iterate locally without a running database. Docker Compose brings up the full stack — app + pgvector + PostgreSQL + Redis — with a single command.

---

## Stack

| Layer | Technology |
|-------|-----------|
| Runtime | Kotlin / Spring Boot |
| AI Framework | Spring AI |
| LLM | OpenAI `o4-mini` |
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
AimuroChatServiceImpl
  └─ Spawns virtual thread for async generation
  └─ Returns requestId + opens SSE via Redis stream
      │
      ▼
CardServiceAdvisor (highest precedence)
  └─ Pre-flight LLM call with tool access
       ├─ findCard(name) → GraphQL
       └─ findCards(filter) → GraphQL
  └─ Enriches prompt with card data if relevant
      │
      ▼
GundamAdvisor
  └─ Classifies question: SIMPLE / MODERATE / IN_DEPTH
  └─ Adjusts top-K (6 / 10 / 16)
  └─ Semantic search → pgvector
  └─ Injects rules excerpts into prompt
      │
      ▼
OpenAI o4-mini
  └─ Streams chunks → ChatStreamProducer → Redis stream:{requestId}
  └─ On complete: saves to PostgreSQL, writes done sentinel, sets TTL
      │
      ▼
ChatStreamConsumer
  └─ Reads Redis stream (100ms poll)
  └─ Terminates on done sentinel
  └─ Emits final isComplete=true event to client

GET /ask/{requestId}/stream  → Reconnect / replay from Redis
```

---

## Getting Started

```bash
# Full stack (Docker)
./aimuro-build.sh
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
You can also use the gundamhub-k8 to run this as part of a cluster. Set `OPEN_AI_KEY`, `SPRING_DATASOURCE_URL`, `APP_CONVERSATION_DATASOURCE_URL`, `SPRING_DATA_REDIS_HOST`, and `SPRING_DATA_REDIS_PORT` as environment variables.


