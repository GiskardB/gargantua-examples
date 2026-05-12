# 3-Layer Memory — Gargantua feature example

Per-feature demo of the framework's three-layer memory subsystem:

| Layer       | What it stores                                      | Default backend (production) | Default backend (embedded) |
|-------------|------------------------------------------------------|------------------------------|-----------------------------|
| `WORKING`   | Current session messages (chronological)             | Redis (TTL-based expiry)     | `InMemoryWorkingMemoryAdapter` |
| `EPISODIC`  | Compressed summaries of past sessions                 | MongoDB (`session_summaries`)| `InMemoryEpisodicMemoryAdapter` |
| `KNOWLEDGE` | Persistent user facts / preferences (CRUD)           | MongoDB (`user_knowledge`)   | `InMemoryKnowledgeMemoryAdapter` |

The `MemoryComposer` fetches enabled layers in **parallel**, applies a
**priority-based token-budget truncation** (knowledge → episodic →
working never trimmed), and emits a single `ComposedMemory` ready for
the prompt composer. A skill can opt out of layers it doesn't need by
declaring `metadata.memory-layers: [working]` (or any subset) in
SKILL.md — the disabled layers' backing ports are never called.

---

## What this example ships

- **Three skills** with different memory profiles:
  - `assistant-skill` — no `memory-layers` declared → composer fetches
    all three.
  - `greeter-skill` — declares `memory-layers: [working]` → composer
    skips episodic and knowledge entirely.
  - `default-skill` — fallback, no memory-layers declared.
- **`NoopTool`** — single placeholder `@AgentTool echo` so the linter
  resolves `allowed-tools`.

---

## What the test suite proves

[`MemoryLayersApplicationTest.java`](src/test/java/ai/gargantua/example/memorylayers/MemoryLayersApplicationTest.java)
boots the full Spring context in the `embedded` profile and pins each
layer + the composer:

| Test method                                       | Pins                                                                                                                  |
|---------------------------------------------------|------------------------------------------------------------------------------------------------------------------------|
| `workingMemoryRoundTrip`                          | `appendMessage` + `getMessages` chronological order, `role` round-trip.                                               |
| `workingMemoryClear`                              | `clear(sessionId)` empties the session.                                                                                |
| `episodicMemoryRoundTrip`                         | `getRecentSummaries` returns newest-first, capped by `limit`.                                                         |
| `knowledgeMemoryRoundTrip`                        | `upsertSegment` overwrites the same key; `deleteSegment` removes one entry without touching others.                    |
| `composerFetchesAllLayers`                        | With no layer restriction, all three layers populate the `ComposedMemory`.                                            |
| `composerSkipsDisabledLayers`                     | With `EnumSet.of(WORKING)`, episodic and knowledge are returned empty (the ports are not consulted).                  |
| `composerTruncatesKnowledgeFirst`                 | Over-budget memory drops knowledge segments first, leaves episodic intact when knowledge alone is enough.             |
| `composerTruncatesEpisodicAfterKnowledge`         | When even after exhausting knowledge the budget is still exceeded, episodic is trimmed (oldest first).                |
| `skillCardEnabledLayersFromFrontmatter`           | `metadata.memory-layers: [working]` in SKILL.md round-trips to `SkillCard.enabledMemoryLayers = {WORKING}`.           |
| `skillCardNullEnabledLayersMeansAll`              | A SKILL.md without `memory-layers` leaves the field `null` so the composer defaults to all three layers.               |

Each test uses a fresh `userId` + `sessionId` (`UUID.randomUUID()`) so
in-memory adapters can't pollute each other across tests.

---

## Run it

### A. Verify the feature (no Docker, no LLM, no Internet)

```bash
cd agent-example-memory-layers
mvn -U test
```

Ten assertions, ~10 s build, no infrastructure.

### B. Persistent memory with Redis + MongoDB (Docker)

```bash
cd agent-example-memory-layers
cp .env.example .env
docker compose up -d
```

Then have a couple of conversations on the same `X-Session-Id` and
check what the composer assembles.

### C. Embedded mode on the host

```bash
SPRING_PROFILES_ACTIVE=embedded mvn spring-boot:run
```

---

## How `memory-layers` saves I/O in production

The default Mongo `EpisodicMemoryPort` and `KnowledgeMemoryPort` issue
one query each per request. A skill that handles greetings doesn't
benefit from either, so declaring `memory-layers: [working]` in its
SKILL.md eliminates two MongoDB round-trips per greeting. Multiplied
across thousands of greetings a day, that's a meaningful saving on
both latency and cluster load.

The `greeter-skill` here is the model: declare your stateless skills
with the smallest enabled-layer set you can get away with.

---

## What this example deliberately does NOT show

| Concern                                       | Where to look                                                                |
|-----------------------------------------------|-------------------------------------------------------------------------------|
| LLM-generated session summaries (background) | The framework's `RoutingModelSessionSummarizer` runs on a `@Scheduled` job over expired sessions. Live demo via docker compose with a routing model. |
| RAG-style knowledge (vector store)           | [`agent-example-rag`](../agent-example-rag/) — `KnowledgeMemoryPort` is for stable user facts, not retrieval-time chunks. |
| Skill metadata other than memory-layers      | [`agent-example-skill-filesystem`](../agent-example-skill-filesystem/).      |
