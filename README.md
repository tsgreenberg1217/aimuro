# AiMURO
<div>
<img align="left" src="https://github.com/user-attachments/assets/1f428f2b-fa3f-4534-bc7f-d2935fd12b34" alt="aimuro" width="200">
<h3>AI-powered rules assistant for the Gundam Trading Card Game.</h3>

AiMURO answers natural-language rules questions with the accuracy of a tournament judge and the personality of a friendly anime robot. It combines semantic search over official rules documents with real-time card data retrieval, grounding every answer in authoritative source material.

</div>

<br></br>

# Technical Highlights

### Multi-Stage Retrieval-Augmented Generation (RAG)

The answer pipeline runs through three coordinated advisors before the model generates a response:

1. **Card Enrichment (pre-flight LLM call)** — `CardServiceAdvisor` analyzes the user's question and, if a specific card is mentioned, fetches live card data via GraphQL before the main query executes. The enriched card attributes (type, level, cost, color, traits, effects) are stored in the advisor context map for downstream stages.

2. **Adaptive Vector Search** — `GundamAdvisor` classifies question complexity (SIMPLE / MODERATE / IN_DEPTH) with a lightweight LLM call, then dynamically retrieves top-K chunks (10 / 16 / 20) from the pgvector store. Results are written to the advisor context map rather than directly into the prompt, keeping retrieval cleanly decoupled from prompt assembly.

3. **Prompt Assembly** — `PromptAssemblerAdvisor` reads both the rules context and card data from the context map and renders the final user message using `rules-advisor-template.txt`. This separates prompt construction from retrieval logic and makes the template independently editable.

The result: retrieval depth scales with question complexity automatically, and each stage has a single, focused responsibility.

### Discriminative Embedding Strategy

Rules documents are split at `#####` heading boundaries — each heading (e.g. a keyword effect like `<Suppression>`) becomes its own embedded chunk. Two key decisions keep embeddings semantically sharp:

- **No breadcrumb prefixes** — a previous approach prepended the full ancestor path (e.g. `13) Keyword Effects > 13-1. Keyword Effects > 13-1-7. <Suppression>`) to every chunk. Because all sibling chunks share the same prefix, their embeddings cluster together, degrading search precision. The prefix is gone.
- **Section numbers stripped** — numeric prefixes are removed from both titles and body lines so the concept term (`<Suppression>`, `<Blocker>`, etc.) dominates the embedding rather than structural noise.

Each chunk is embedded directly at `#####` granularity without further token-splitting — these sections are compact enough that splitting only hurts coherence.

### Agentic Tool Calling

The card enrichment stage uses Spring AI's `@Tool` annotation to expose two callable functions to the LLM:

- `findCard(name)` — exact card lookup by name
- `findCards(filter)` — filtered search by color, level, cost, and unit trait

The model decides autonomously whether card data is needed and which tool to invoke — a lightweight agentic loop running as a preprocessing step before the main response.

### Live Card Data via GraphQL

Card lookups hit a live GraphQL API (`GundamCardGraphQlClient`) rather than a static snapshot, ensuring card text and attributes always reflect the current card database.

### Externalized Prompts

All prompt strings live in `src/main/resources/prompts/*.txt` and are loaded lazily at startup. The system prompt, rules advisor template, card enrichment prompts, and classification prompts can all be edited without recompiling.

### Resilient SSE Streaming

Rather than piping the AI response directly to an SSE connection, AIMURO decouples generation from delivery using Redis Streams:

1. `POST /ask` kicks off generation on a virtual thread and immediately opens an SSE connection backed by a Redis stream.
2. Each response chunk is published to `stream:{requestId}` as it arrives. A `done=true` sentinel closes the consumer.
3. If the client disconnects mid-stream, it can reconnect via `GET /ask/{requestId}/stream` — the stream is replayed from the beginning, and the 10-minute TTL on completed streams means late fetches still work.

This means a dropped connection never loses a response.

### Production-Ready Infrastructure, Debug-Friendly Development

| Mode | Vector Store | LLM / Embeddings | Notes |
|------|-------------|-----------------|-------|
| `openai` | PgVector (pgvector pg18) | OpenAI o4-mini / text-embedding-3-small | Requires `OPEN_AI_KEY` |
| `ollama` | PgVector (pgvector pg18) | Ollama llama3.1 / qwen3-embedding:0.6b | Runs fully locally, no API key |
| `debug` | In-memory `SimpleVectorStore` | Configurable | No DB or Redis needed |

Spring profiles let engineers iterate locally without a running database. Docker Compose brings up the full stack — app + pgvector + PostgreSQL + Redis — with a single command.

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
AimuroChatServiceImpl
  └─ Spawns virtual thread for async generation
  └─ Returns requestId + opens SSE via Redis stream
      │
      ▼
CardServiceAdvisor (highest precedence)
  └─ Pre-flight LLM call with tool access
       ├─ findCard(name) → GraphQL
       └─ findCards(filter) → GraphQL
  └─ Stores card data in advisor context map
      │
      ▼
GundamAdvisor
  └─ Classifies question: SIMPLE / MODERATE / IN_DEPTH
  └─ Adjusts top-K (10 / 16 / 20)
  └─ Semantic search → pgvector
  └─ Stores rules context in advisor context map
      │
      ▼
PromptAssemblerAdvisor
  └─ Reads rules context + card data from context map
  └─ Renders final user message via rules-advisor-template.txt
      │
      ▼
LLM (OpenAI o4-mini or Ollama llama3.1)
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
# Full stack with OpenAI (Docker)
OPEN_AI_KEY=your-key ./aimuro-build.sh

# Full stack with Ollama (requires Ollama running locally on port 11434)
./aimuro-build.sh  # set spring.profiles.active=ollama in application.yaml first

# Debug mode — no database or Redis required
./gradlew bootRun --args='--spring.profiles.active=debug'
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
