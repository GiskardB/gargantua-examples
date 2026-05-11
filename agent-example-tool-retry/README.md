# `@ToolRetry` — Gargantua feature example

Per-feature demo of `@ToolRetry`: the annotation that wraps a tool method
in a Resilience4j retry policy with exponential backoff. **One tool class,
one skill, zero external services**, fully verifiable with `mvn test`.

> Sibling examples: [`agent-example-tool-basics`](../agent-example-tool-basics/)
> for `@AgentTool` itself. This module focuses only on the retry behaviour.

---

## The feature: what `@ToolRetry` does

`@ToolRetry` is a method-level annotation that tells the framework to wrap
each call to that tool in a Resilience4j `Retry` decorator. Source:
[`ToolRetry.java`](https://github.com/GiskardB/gargantua/blob/main/agent-core/src/main/java/ai/gargantua/core/tool/ToolRetry.java).

```java
@Target(METHOD)
@Retention(RUNTIME)
public @interface ToolRetry {
    int     maxAttempts()       default 3;          // inclusive of the first call
    long    waitDurationMs()    default 500;        // initial backoff
    double  backoffMultiplier() default 2.0;        // exponential growth factor
    long    maxWaitDurationMs() default 5000;       // upper bound on each delay
    Class<? extends Throwable>[] retryOn() default {IOException.class};
    Class<? extends Throwable>[] abortOn() default {IllegalArgumentException.class};
}
```

### How it gets applied

At boot, `ToolRegistry.scan()` caches the `@ToolRetry` instance alongside the
tool's `Method` reference in a `ToolInvocation` record (one reflective lookup
per tool, not per call). On each LLM-driven tool call:

1. RBAC and cache layers run first (no-op here).
2. If `@ToolRetry` is present, `ToolRegistry.executeWithRetry(...)` builds a
   Resilience4j `Retry` with the annotation's parameters and decorates the
   invocation supplier.
3. The retry predicate is:
   `if (matches(cause, abortOn)) return false; return matches(cause, retryOn);`
   — `abortOn` is checked first, so listing an exception in both effectively
   means "abort".
4. Every retry attempt increments the Micrometer counter
   `agent.tool.retry.attempts{tool=…}`. When the budget is exhausted, the
   counter `agent.tool.retry.exhausted{tool=…}` ticks and the registry
   returns a structured `{"error":"Tool execution failed: …"}` payload (so
   the LLM gets a recoverable signal — see the v1.2.3 release notes).

### Knobs at a glance

| Attribute | Default | What it controls |
|-----------|---------|------------------|
| `maxAttempts` | `3` | Total invocations including the first. Setting it to `1` disables retry. |
| `waitDurationMs` | `500` | Initial wait before the second attempt. |
| `backoffMultiplier` | `2.0` | Each wait is multiplied by this. `2.0` → `500ms, 1000ms, 2000ms…` |
| `maxWaitDurationMs` | `5000` | Caps individual delays so backoff never runs away. |
| `retryOn` | `{IOException.class}` | Which exception types are retried. |
| `abortOn` | `{IllegalArgumentException.class}` | Which exception types short-circuit retries. Checked **before** `retryOn`. |

---

## What this example demonstrates

`FlakyTool` exposes four `@AgentTool` methods, one per branch of the retry
contract. Source:
[`FlakyTool.java`](src/main/java/ai/gargantua/example/toolretry/tools/FlakyTool.java).

| Tool | `@ToolRetry` configuration | Throws | Expected outcome |
|------|---------------------------|--------|------------------|
| `flakyAdd(a, b)` | `maxAttempts=3, waitDurationMs=10, retryOn={IOException}` | `IOException` × 2, then succeeds | Sum returned, tool ran **3 times** |
| `alwaysFail(reason)` | same | `IOException` always | `{"error":"…"}` after **3 invocations**, exhaustion counter +1 |
| `failWithNonRetriedException(reason)` | same | `IllegalStateException` | `{"error":"…"}`, **1 invocation** (not in `retryOn`, not in `abortOn`) |
| `failWithAbortException(reason)` | `retryOn={Exception}, abortOn={IllegalStateException}` | `IllegalStateException` | `{"error":"…"}`, **1 invocation** (`abortOn` wins over the wide `retryOn`) |

Only `flakyAdd` is exposed to the LLM via the skill's `allowed-tools` list —
the other three exist purely to give the test suite a way to assert the
non-happy branches.

---

## What the test suite proves

[`ToolRetryApplicationTest.java`](src/test/java/ai/gargantua/example/toolretry/ToolRetryApplicationTest.java)
boots the full Spring context in the `embedded` profile (no Mongo, no Redis)
and pins every guarantee `@ToolRetry` makes:

| Test method                            | Pins                                                                                          |
|----------------------------------------|-----------------------------------------------------------------------------------------------|
| `allFourToolsAreRegistered`            | The four `@AgentTool` methods are discovered with the correct LLM-facing names.                |
| `retryEventuallySucceeds`              | After 2 simulated failures the third attempt succeeds; the caller gets the real sum.           |
| `retryBudgetIsCappedAtMaxAttempts`     | When failures exceed `maxAttempts`, the tool stops being invoked and `{"error":"…"}` is returned. |
| `retryExhaustedReturnsErrorJson`       | `alwaysFail` runs exactly `maxAttempts` times then surfaces structured JSON.                  |
| `nonRetriedExceptionIsNotRetried`      | An exception not in `retryOn` (nor `abortOn`) skips retries entirely — **one** invocation.    |
| `abortOnShortCircuitsRetries`          | `abortOn` wins over a wide `retryOn=Exception` — **one** invocation.                          |
| `retryMetricsAreIncremented`           | `agent.tool.retry.attempts{tool=…}` and `agent.tool.retry.exhausted{tool=…}` increment correctly. |

`@BeforeEach` resets the per-tool counters so test interactions are independent.

---

## Run it

### A. Verify the feature (no Docker, no LLM, no Internet)

```bash
cd agent-example-tool-retry
mvn -U test
```

Seven assertions, ~10 s build, no infrastructure. **This is the recommended
way to confirm the example works.**

### B. Chat with the agent (Docker, fully-local Ollama)

```bash
cd agent-example-tool-retry
cp .env.example .env
docker compose up -d
```

Then:

```bash
curl -X POST http://localhost:8080/api/agent/chat \
  -H "Content-Type: application/json" \
  -H "X-User-Id: alice" -H "X-Session-Id: s1" \
  -d '{"message": "What is 17 plus 25?"}'
```

The agent will return `42`. In the server logs you'll see two
`IOException: simulated transient failure` lines before the success — the
LLM never knows the call retried.

### C. Embedded mode on the host (no Docker, optional LLM)

```bash
export LLM_PRIMARY_PROVIDER=openai
export LLM_PRIMARY_MODEL=gpt-4o-mini
export LLM_PRIMARY_API_KEY=sk-...
SPRING_PROFILES_ACTIVE=embedded mvn spring-boot:run
```

---

## What this example deliberately does NOT show

| Concern                                | Where to look                                                |
|----------------------------------------|--------------------------------------------------------------|
| `@AgentTool` registration mechanics    | [`agent-example-tool-basics`](../agent-example-tool-basics/) |
| Cached tool results                    | `agent-example-tool-cache` (planned)                         |
| Human approval before execution        | `agent-example-tool-approval` (planned)                      |
| Real external HTTP retries             | [`agent-example-weather`](../agent-example-weather/) uses `@ToolRetry` on real Open-Meteo calls |

The point of this module is the **retry behaviour** in isolation: every
parameter of `@ToolRetry` has a corresponding test method that pins it.
