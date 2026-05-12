# RAG — Gargantua feature example

Per-feature demo of **Retrieval-Augmented Generation**: skills that
declare a `metadata.knowledge-base` in their SKILL.md frontmatter
automatically have the user's message looked up against a vector store
and the top results injected into the system prompt under a
`RELEVANT_DOCUMENTS` section. One ingest seeder, two skills (one with
RAG, one without), and tests that exercise every observable branch
without any external infrastructure.

> Sibling examples: [`agent-example-skill-filesystem`](../agent-example-skill-filesystem/)
> for SKILL.md authoring in general; [`agent-example-routing-strategy`](../agent-example-routing-strategy/)
> for skill selection.

---

## The feature

Three pieces work together:

1. **`SkillCard.ragConfig`** — populated by `SkillMdParser` when the
   frontmatter contains `metadata.knowledge-base`. Optional
   `metadata.rag-max-results` (default 5) and `metadata.rag-min-score`
   (default 0.3) tune the retrieval.
2. **`VectorStorePort`** — the framework's port for vector search. The
   embedded mode wires an `InMemoryVectorStore` with Jaccard token
   similarity (good enough for tests / demos; use pgvector / Qdrant /
   Milvus / etc. in production by providing your own bean).
3. **`RagEnricher`** — a `ContextEnricher` registered automatically by
   `RagAutoConfiguration` whenever both a `VectorStorePort` and a
   `SkillRegistry` are available. For each request it loads the active
   skill, returns `null` if the skill has no `RagConfig`, otherwise
   calls `vectorStore.search(...)` and emits a `RELEVANT_DOCUMENTS`
   section like:

   ```
   The following documents are relevant to the user's question:

   1. [Source: refund-policy.md | Score: 0.42]
   Refunds are processed within 7 business days of approval. ...
   ```

   The orchestrator's `MemoryComposer` slots that string into the system
   prompt next to whatever other enrichers (memory, headers, …)
   contributed.

### Backends

| Backend                  | When wired                                                | Suitability                                                       |
|--------------------------|------------------------------------------------------------|--------------------------------------------------------------------|
| `InMemoryVectorStore`    | embedded mode (`SPRING_PROFILES_ACTIVE=embedded`)         | Tests, demos, prototypes. Jaccard tokens — not semantic.           |
| Custom `VectorStorePort` | provide a `@Bean VectorStorePort` in your app             | Production. Plug pgvector / Qdrant / Milvus / your own client.     |

### What this example does NOT replace

A production ingest pipeline. Real apps chunk documents, compute
embeddings, write them to the vector DB, and re-index on update. This
example uses a `@PostConstruct` `KnowledgeBaseSeeder` with four
hard-coded snippets — enough to drive the contract tests, not a model
for production ingest.

---

## What this example ships

- [`KnowledgeBaseSeeder`](src/main/java/ai/gargantua/example/rag/seed/KnowledgeBaseSeeder.java) —
  populates the `support-faq` collection at boot with 4 policy snippets
  (refund, SLA, password reset, maintenance window).
- [`support-skill`](src/main/resources/skills/support-skill/SKILL.md) —
  declares `metadata.knowledge-base: support-faq`,
  `rag-max-results: 3`, `rag-min-score: 0.05`. Used to verify the
  enricher injects chunks.
- [`default-skill`](src/main/resources/skills/default-skill/SKILL.md) —
  no knowledge-base, control case to prove the enricher is a no-op for
  non-RAG skills.
- [`NoopTool`](src/main/java/ai/gargantua/example/rag/tools/NoopTool.java) —
  one trivial `@AgentTool` so `allowed-tools: [echo]` passes the linter.

---

## What the test suite proves

[`RagApplicationTest.java`](src/test/java/ai/gargantua/example/rag/RagApplicationTest.java)
boots the full Spring context in the `embedded` profile and asserts:

| Test method                               | Pins                                                                                                                  |
|-------------------------------------------|------------------------------------------------------------------------------------------------------------------------|
| `supportSkillHasRagConfig`                | `metadata.knowledge-base` + `rag-*` fields produce a populated `RagConfig` on the SkillCard.                          |
| `defaultSkillHasNoRagConfig`              | A SKILL.md without `knowledge-base` produces `ragConfig() == null`.                                                   |
| `searchReturnsRankedChunks`               | `VectorStorePort.search` returns chunks sorted by score desc; the refund query's top hit is `refund-policy.md`.       |
| `searchRespectsMaxResults`                | `maxResults` caps the returned list.                                                                                  |
| `searchRespectsMinScore`                  | Chunks below `minScore` are dropped (impossible 0.99 → empty).                                                        |
| `searchMissingCollectionReturnsEmpty`     | An unknown collection name returns `[]` rather than throwing.                                                         |
| `enricherProducesSectionForRagSkill`      | `RagEnricher.enrich` for `support-skill` returns a non-null section containing the chunk content + source + score.    |
| `enricherReturnsNullForNonRagSkill`       | `RagEnricher.enrich` for `default-skill` returns `null` — zero overhead on the prompt.                                |
| `enricherReturnsNullWhenNothingMatches`   | Irrelevant query → empty chunks → enricher returns `null`, NOT an empty section.                                       |
| `enricherHonoursMaxResults`               | The injected section never has more than `rag-max-results` numbered entries.                                          |

---

## Run it

### A. Verify the feature (no Docker, no LLM, no Internet)

```bash
cd agent-example-rag
mvn -U test
```

Ten assertions, ~10 s build, no infrastructure.

### B. Chat with retrieval (Docker, fully-local Ollama)

```bash
cd agent-example-rag
cp .env.example .env
docker compose up -d

curl -X POST http://localhost:8080/api/agent/chat \
  -H 'Content-Type: application/json' \
  -H 'X-User-Id: alice' -H 'X-Session-Id: s1' \
  -d '{"message": "How do refunds work?"}'
```

Watch the logs at `DEBUG` for `ai.gargantua.autoconfigure.RagEnricher`
to see the chunks getting selected and scored.

### C. Embedded mode on the host

```bash
SPRING_PROFILES_ACTIVE=embedded mvn spring-boot:run
```

---

## What this example deliberately does NOT show

| Concern                                  | Where to look                                                                |
|------------------------------------------|------------------------------------------------------------------------------|
| Document chunking / embedding ingestion  | Replace `KnowledgeBaseSeeder` with your real pipeline.                       |
| Production vector backends               | Provide your own `@Bean VectorStorePort` (pgvector / Qdrant / Milvus / …).   |
| Re-indexing on skill or doc updates      | Hot-reload + custom backends; framework hook is `SkillReloadedEvent`.        |
| Skill metadata other than RAG            | [`agent-example-skill-filesystem`](../agent-example-skill-filesystem/).      |
