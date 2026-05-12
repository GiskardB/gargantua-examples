# Cost Tracking + Audit Trail — Gargantua feature example

Per-feature demo of two production observability subsystems pinned
together because they share a wiring model:

- **`CostTracker`** — looks up per-model pricing from
  `agent.cost-tracking.pricing` and estimates USD cost per LLM call.
- **`AuditService` + `AuditStore`** — append-only audit log written
  after every agent invocation, queryable by user / tenant / session
  / time-range / id.

Both are pinned end-to-end by direct calls to the autowired beans —
no LLM is invoked.

---

## The feature

### CostTracker

Enabled by `agent.cost-tracking.enabled: true`. Reads a flat
`Map<String, Double>` under `agent.cost-tracking.pricing` whose keys
encode per-1000-token rates. `CostTracker.estimateUsd(provider, model,
inputTokens, outputTokens)` tries these key forms **in order**:

1. `<provider>.<model>.input-per-1k-tokens` / `…output-per-1k-tokens` (nested)
2. `<provider>:<model>:input-per-1k-tokens` / `…output-per-1k-tokens` (colon)
3. `<model>.input-per-1k-tokens` / `…output-per-1k-tokens` (model-only)
4. `<model>:input-per-1k-tokens` / `…output-per-1k-tokens`

The first matching pair wins; the estimate is
`(inputTokens / 1000) × inputRate + (outputTokens / 1000) × outputRate`.
When tracking is disabled, `estimateUsd` returns `0.0` regardless of
pricing.

> **YAML key quoting note.** Because Spring's relaxed binder treats
> dots and colons as structural, the keys must be **bracketed** in
> YAML to be stored verbatim:
> `"[openai.gpt-4o.input-per-1k-tokens]": 0.005`. See
> [`application.yml`](src/main/resources/application.yml) for the
> canonical form.

### AuditService + AuditStore

Enabled by `agent.audit.enabled: true` (default). After every
`DefaultOrchestratorEngine.invoke()`, `AuditService.recordRequest`
builds an immutable `AuditEvent` and hands it to the wired
`AuditStore`. The event captures the full decision chain:

| Field             | Source                                                          |
|-------------------|------------------------------------------------------------------|
| `eventId`         | random UUID                                                      |
| `timestamp`       | `Instant.now()`                                                  |
| `userId` / `tenantId` | `AgentRequest.userId()` / `SecurityContext.tenantId()`        |
| `sessionId`       | `AgentRequest.sessionId()`                                        |
| `userMessage`     | `AgentRequest.message()` (after PII masking if enabled)          |
| `agentResponse`   | `AgentResponse.text()`                                            |
| `skillSelected`   | `AgentResponse.skillUsed()`                                       |
| `routingMethod`   | `RoutingResult.method().name()` (`SEMANTIC` / `LLM` / `FORCED`)   |
| `routingConfidence` | `RoutingResult.confidence()`                                   |
| `toolsCalled`     | `AgentResponse.toolsCalled()`                                     |
| `guardrailEvents` | mapped from each `GuardrailResult` (`name`, `verdict`, `reason`) |
| `inputTokens` / `outputTokens` | `AgentResponse.*Tokens()`                            |
| `estimatedCostUsd` | `AgentResponse.estimatedCostUsd()`                              |
| `durationMs` / `dryRun` | `AgentResponse.*`                                          |

In embedded mode the `AuditStore` is the `InMemoryAuditStore`
contributed by `EmbeddedProfileAutoConfiguration`; in production it is
the `MongoAuditStore` (collection `audit_trail`). The store API is
identical either way:

- `record(event)` — append-only.
- `findById(eventId)` — point lookup.
- `findByUser(userId, from, to, limit)` — time-range query, newest first.
- `findByTenant(tenantId, from, to, limit)` — multi-tenant filter.
- `findBySession(sessionId)` — full session history, newest first.
- `countByTimeRange(from, to)` — dashboard counter.

> **v1.2.12 wiring note.** `AuditService` is now registered
> unconditionally when `agent.audit.enabled=true`; the `AuditStore`
> dependency is resolved via `ObjectProvider` at bean-creation time
> instead of `@ConditionalOnBean(AuditStore.class)` on the `@Bean`
> method (which previously raced with the profile-gated
> `EmbeddedProfileAutoConfiguration` and silently dropped the bean in
> embedded apps). When no store is wired, `AuditService.isActive()`
> returns `false` and `recordRequest` short-circuits silently. Use
> **v1.2.12 or later** for this example.

---

## What this example ships

[`application.yml`](src/main/resources/application.yml) declares pricing
in **all three** lookup forms so each test can hit a specific path:

| Key                                                                  | Form           |
|----------------------------------------------------------------------|----------------|
| `[openai.gpt-4o.input-per-1k-tokens]`: `0.005`                       | nested         |
| `[openai.gpt-4o.output-per-1k-tokens]`: `0.015`                      | nested         |
| `[anthropic:claude-sonnet-4-20250514:input-per-1k-tokens]`: `0.003`  | colon          |
| `[anthropic:claude-sonnet-4-20250514:output-per-1k-tokens]`: `0.015` | colon          |
| `[gpt-4o-mini.input-per-1k-tokens]`: `0.00015`                       | model-only     |
| `[gpt-4o-mini.output-per-1k-tokens]`: `0.00060`                      | model-only     |

A trivial `default-skill` and placeholder `NoopTool` keep the agent
context bootable. The tests bypass the orchestrator entirely.

---

## What the test suite proves

[`CostAndAuditApplicationTest.java`](src/test/java/ai/gargantua/example/costandaudit/CostAndAuditApplicationTest.java)
boots the Spring context in the `embedded` profile (16 assertions):

| Test method                                  | Pins                                                                        |
|----------------------------------------------|-----------------------------------------------------------------------------|
| `costEnabledAndPricingResolvesNestedForm`    | `<provider>.<model>.suffix` key form resolves; arithmetic is exact.         |
| `costEnabledAndPricingResolvesColonForm`     | `<provider>:<model>:suffix` key form resolves.                              |
| `costEnabledModelOnlyFallback`               | Falls back to `<model>.suffix` when no provider-prefixed key matches.       |
| `exactUsdArithmetic`                         | 500 + 500 tokens → exactly half of the 1k+1k cost.                          |
| `unknownModelEstimateIsZero`                 | No pricing entry → `0.0` (silent, no exception).                            |
| `disabledTrackingShortCircuitsToZero`        | When `agent.cost-tracking.enabled=false`, `estimateUsd` returns `0.0`.       |
| `auditStoreRecordAndFindById`                | `record(event)` + `findById(eventId)` round-trips by value.                  |
| `findByUserRespectsTimeRange`                | Older-than-window events excluded.                                          |
| `findByUserRespectsLimit`                    | Limit clamps result size.                                                   |
| `findByTenantFilters`                        | Multi-tenant isolation by `tenantId`.                                       |
| `findBySessionReturnsNewestFirst`            | Session history sorted newest-first.                                        |
| `countByTimeRangeMatchesStoredEvents`        | Count reflects events whose timestamp is in `[from, to]`.                   |
| `guardrailEventsRoundTrip`                   | `List<GuardrailEvent>` (name, verdict, reason) round-trips intact.          |
| `auditServiceMapsRequestToEventIncludingGuardrails` | `recordRequest` builds an `AuditEvent` with the right fields and stores it; `GuardrailResult` → `GuardrailEvent` mapping intact. |
| `auditServiceShortCircuitsWhenDisabled`      | When `agent.audit.enabled=false`, `recordRequest` is a no-op.               |
| `bothFeaturesEnabledByYaml`                  | Both features are active and pricing is populated from `application.yml`.   |

---

## Run it

### A. Verify the feature (no Docker, no LLM, no Internet)

```bash
cd agent-example-cost-and-audit
mvn -U test
```

16 assertions, ~10 s build, no infrastructure.

### B. Live (Docker, fully-local Ollama)

```bash
cd agent-example-cost-and-audit
cp .env.example .env
docker compose up -d
```

Each chat call writes a real `AuditEvent` to the `audit_trail`
collection in MongoDB. The `GET /api/admin/audit/*` endpoints expose
the same queries the tests use. The `CostTracker` populates
`AgentResponse.estimatedCostUsd` on every response when pricing is
configured.

### C. Embedded mode on the host

```bash
SPRING_PROFILES_ACTIVE=embedded mvn spring-boot:run
```

---

## What this example deliberately does NOT show

| Concern                                            | Where to look                                                                          |
|----------------------------------------------------|----------------------------------------------------------------------------------------|
| `MongoAuditStore` indexes / pagination semantics   | Production-grade; covered by `MongoAuditStoreTest` in the framework repo.              |
| Cost dashboards / Prometheus integration           | `agent.cost.*` Micrometer counters are emitted; surfacing them in Grafana is out of scope. |
| `GET /api/admin/costs/*` REST surface              | Wired by `CostTrackingAutoConfiguration`; the tests bypass HTTP.                       |
| `AuditAdminController` REST queries                | Same — tested at the framework level; the example pins the store API directly.         |
| `CostTracker.record(...)` (event sink)             | Currently a log-only placeholder; the persisting variant lives in the cost-tracking repo. |
