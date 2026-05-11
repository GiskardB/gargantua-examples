# `@CacheableToolResult` — Gargantua feature example

Per-feature demo of `@CacheableToolResult`: the annotation that wraps a
tool method in a read-through cache. **One tool class, one skill, zero
external services** — fully verifiable with `mvn test` thanks to the
in-memory cache backend introduced in framework v1.2.5.

> Sibling examples: [`agent-example-tool-basics`](../agent-example-tool-basics/),
> [`agent-example-tool-retry`](../agent-example-tool-retry/).

---

## The feature: what `@CacheableToolResult` does

`@CacheableToolResult` is a method-level annotation that wraps each tool
call in a read-through cache. Source:
[`CacheableToolResult.java`](https://github.com/GiskardB/gargantua/blob/main/agent-core/src/main/java/ai/gargantua/core/tool/CacheableToolResult.java).

```java
@Target(METHOD)
@Retention(RUNTIME)
public @interface CacheableToolResult {
    int        ttlSeconds() default 300;
    String[]   keyParams()  default {};   // empty = hash all parameters
    CacheScope scope()      default CacheScope.GLOBAL;
}
```

### Backends

Two interchangeable storage backends ship with the framework, both
exposed through the same `ToolResultCache` class:

| Backend | Active when | Survives restart | Shared across replicas |
|---------|-------------|------------------|------------------------|
| **Redis** (`StringRedisTemplate`) | a Redis bean is present (production default) | ✅ | ✅ |
| **In-memory** (`ConcurrentHashMap` + per-entry TTL) | embedded mode (`SPRING_PROFILES_ACTIVE=embedded`) | ❌ | ❌ |

The in-memory backend is wired by `EmbeddedProfileAutoConfiguration` —
same contract as the Redis one (read-through, per-entry TTL, scoped
keys), only the persistence is different. **That's the backend this
example's tests use.** No Docker required.

### Scopes

`CacheScope` controls the isolation boundary of each entry:

| Scope | Isolation | Use for |
|-------|-----------|---------|
| `GLOBAL` (default) | Shared across all users and sessions. | Stateless public lookups: exchange rates, weather, ticker info. |
| `USER` | One entry per user. | Per-user preferences, profile lookups. Requires a `SecurityContext`. |
| `SESSION` | One entry per conversation. | Context-dependent results within a single chat. Requires a session id. |

If the required context is missing (USER without a `SecurityContext`,
SESSION without a `sessionId`) the registry **bypasses the cache
entirely** — every call invokes the tool body. This is also pinned in
the test suite.

### How it gets applied

1. At boot, `ToolRegistry.scan()` caches the `@CacheableToolResult`
   annotation next to the tool's `Method` reference.
2. On each invocation, `ToolRegistry.executeTool(...)`:
   - Builds a key via `ToolResultCache.buildKey(...)` — returns
     `null` when the scope can't be honoured.
   - **Read-through**: `cache.get(key)` first; on a hit returns the
     cached payload and bumps `agent.tool.cache.hits{tool, scope}`.
   - On a miss, runs the tool body (including any `@ToolRetry` wrapping),
     stores the result with `cache.put(key, value, ttlSeconds)`, and
     bumps `agent.tool.cache.misses{tool, scope}`.
3. Cache entries live under the `tool-cache:` Redis prefix so the
   existing `/api/admin/tool-cache/*` endpoints can list / clear them.

### Key derivation

The cache key is built from:
- the scope segment (`global`, `user:<id>`, `session:<id>`)
- the tool name
- a SHA-256 hash of the argument subset declared in `keyParams`
  (sorted by parameter name; empty `keyParams` = all parameters)

This means parameters NOT listed in `keyParams` are **silently ignored**
for the cache key — useful for "display-only" parameters that shouldn't
fragment the cache.

---

## What this example demonstrates

`CounterTool` ([source](src/main/java/ai/gargantua/example/toolcache/tools/CounterTool.java))
exposes five `@AgentTool` methods, each isolating one aspect of the
annotation. Every method increments a counter on every actual invocation —
counted misses, not LLM-visible calls — so tests can assert exactly how
many times the tool body ran.

| Method | `@CacheableToolResult` configuration | What it proves |
|--------|---------------------------------------|----------------|
| `lookupGlobal(ticker)` | `ttl=60, keyParams=ticker, GLOBAL` | Basic hit on the second call with the same args. |
| `lookupWithKeyParamSubset(ticker, currency)` | `ttl=60, keyParams=ticker, GLOBAL` | `currency` is not in `keyParams` → still hits. |
| `lookupShortTtl(key)` | `ttl=1, GLOBAL` | Expiry: after 1 s the tool runs again. |
| `lookupForUser(category)` | `ttl=60, keyParams=category, USER` | USER scope isolates per user. Also: missing security context → cache skipped. |
| `lookupForSession(query)` | `ttl=60, keyParams=query, SESSION` | SESSION scope isolates per session id. |

Only `lookupGlobal` is exposed to the LLM via `allowed-tools` — the
other four exist purely for tests.

---

## What the test suite proves

[`ToolCacheApplicationTest.java`](src/test/java/ai/gargantua/example/toolcache/ToolCacheApplicationTest.java)
boots the full Spring context in the `embedded` profile and pins every
guarantee the annotation makes:

| Test method                                | Pins                                                                                          |
|--------------------------------------------|-----------------------------------------------------------------------------------------------|
| `secondCallHitsCache`                      | Second call with the same args returns the cached value; tool body runs once.                  |
| `distinctArgsAreDistinctSlots`             | AAPL and MSFT each occupy their own slot; repeats hit.                                        |
| `keyParamSubsetIgnoresExtras`              | Same `ticker` with different `currency` (not in `keyParams`) still hits the cache.            |
| `ttlExpiryForcesReinvocation`              | After 1 s with `ttlSeconds=1` the entry is gone — tool body runs again.                       |
| `userScopeIsolatesPerUser`                 | Two distinct `SecurityContext`s have independent cache slots for the same category.            |
| `userScopeWithoutContextSkipsCache`        | USER scope + empty context → cache bypassed entirely, every call invokes the tool.             |
| `sessionScopeIsolatesPerSession`           | Two distinct session ids have independent cache slots for the same query.                     |
| `hitMissCountersIncrement`                 | `agent.tool.cache.hits/misses{tool, scope}` increment as expected.                            |

---

## Run it

### A. Verify the feature (no Docker, no LLM, no Internet)

```bash
cd agent-example-tool-cache
mvn -U test
```

Eight assertions, ~12 s build (the TTL test waits 1.1 s), zero
infrastructure. **This is the recommended way to confirm the example
works.**

### B. Chat with the agent (Docker, fully-local Ollama + Redis)

```bash
cd agent-example-tool-cache
cp .env.example .env
docker compose up -d
```

Then:

```bash
curl -X POST http://localhost:8080/api/agent/chat \
  -H "Content-Type: application/json" \
  -H "X-User-Id: alice" -H "X-Session-Id: s1" \
  -d '{"message": "Look up AAPL"}'

# Ask again — second response includes calls=1 (cache hit)
curl -X POST http://localhost:8080/api/agent/chat \
  -H "Content-Type: application/json" \
  -H "X-User-Id: alice" -H "X-Session-Id: s1" \
  -d '{"message": "Look up AAPL again please"}'

# Inspect the Redis cache directly
docker compose exec redis redis-cli KEYS 'tool-cache:*'
```

### C. Embedded mode on the host (no Docker, optional LLM)

```bash
SPRING_PROFILES_ACTIVE=embedded mvn spring-boot:run
```

In embedded mode the in-memory `ToolResultCache` bean is wired
automatically — same contract as the Redis one.

---

## What this example deliberately does NOT show

| Concern                                | Where to look                                                |
|----------------------------------------|--------------------------------------------------------------|
| `@AgentTool` registration mechanics    | [`agent-example-tool-basics`](../agent-example-tool-basics/) |
| Transparent retries on flaky tools     | [`agent-example-tool-retry`](../agent-example-tool-retry/)   |
| Human approval before execution        | `agent-example-tool-approval` (planned)                      |
| Role-based access control              | `agent-example-tool-rbac` (planned)                          |

The point of this module is the **cache contract** in isolation: every
attribute of `@CacheableToolResult` has a corresponding test method
that pins it, and the in-memory backend lets `mvn test` exercise the
end-to-end path with no Redis required.
