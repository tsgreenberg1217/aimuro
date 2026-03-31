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

### Streaming Responses

The `/rulesStream` endpoint returns Server-Sent Events so the frontend can render answers token-by-token, matching the UX feel of modern AI assistants.

### Production-Ready Infrastructure, Debug-Friendly Development

| Mode | Vector Store | Database |
|------|-------------|----------|
| Default | PgVector (PostgreSQL + pgvector) | PostgreSQL pg16 |
| `debug` profile | In-memory `SimpleVectorStore` | None required |

Spring profiles let engineers iterate locally without a running database. Docker Compose brings up the full stack — app + PostgreSQL — with a single command.

---

## Stack

| Layer | Technology |
|-------|-----------|
| Runtime | Kotlin / Spring Boot |
| AI Framework | Spring AI |
| LLM | OpenAI `o4-mini` |
| Vector Store | PostgreSQL + pgvector |
| Card Data | GraphQL API |
| Containerization | Docker / Docker Compose |

---

## Architecture

```
User Question
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
  └─ Generates grounded answer
      │
      ▼
POST /rules        → Single response
POST /rulesStream  → Server-Sent Events
```

---

## Getting Started

```bash
# Full stack (Docker)
./aimuro-build.sh
```

App runs on `localhost:8080` (local) or `localhost:8000` (Docker).

---

## API

Both endpoints accept a conversation history array to support multi-turn dialogue:

```bash
POST /rules
POST /rulesStream

{
  "conversation": [
    { "role": "user",      "content": "Can my Gundam attack the turn it's played?" },
    { "role": "assistant", "content": "..." }
  ]
}
```
`/rulesStream` returns `text/event-stream` (SSE).

## K8
you can also use the gundamhub-k8 to run this as part of a cluster, but make sure to set the environment variables for the OpenAI API key and the database connection string.


