# `@RequiresApproval` — Gargantua feature example

Per-feature demo of `@RequiresApproval`: the annotation that pauses a
tool invocation, alerts a human reviewer, and lets the action proceed
only after the reviewer approves via the REST API. One tool class, one
skill, and an integration test that exercises the metadata + the
`ApprovalStore` lifecycle + the REST resolution endpoint without
needing Redis, MongoDB, or an LLM.

> Sibling examples: [`agent-example-tool-basics`](../agent-example-tool-basics/),
> [`agent-example-tool-retry`](../agent-example-tool-retry/),
> [`agent-example-tool-cache`](../agent-example-tool-cache/).

---

## The feature: what `@RequiresApproval` does

`@RequiresApproval` is a method-level annotation that tags a tool as
"human-in-the-loop". Source:
[`RequiresApproval.java`](https://github.com/GiskardB/gargantua/blob/main/agent-core/src/main/java/ai/gargantua/core/tool/RequiresApproval.java).

```java
@Target(METHOD)
@Retention(RUNTIME)
public @interface RequiresApproval {
    String   message()        default "";    // shown to the human reviewer
    String[] showParameters() default {};    // ⚠ declared but not yet applied — see open gaps
    boolean  dangerous()      default false; // UI hint for high-risk actions
}
```

### How the pause works (streaming chat path)

The `/api/agent/chat/stream` SSE controller drives the HITL flow. For each
tool call the LLM emits:

1. The orchestrator looks up the `ToolDefinition` and checks `requiresApproval`.
2. If `true`:
   - It generates a random `requestId` (UUID).
   - Saves a pending `ApprovalRequest` into the `ApprovalStore` with a TTL
     from `agent.hitl.default-ttl-minutes` (default 5 min).
   - Emits an SSE event of type `approval_required` containing the
     `requestId`, the tool name, the raw arguments JSON, the
     human-readable `message`, the `dangerous` flag, and the `ttlMinutes`.
   - Returns `{"status":"awaiting_approval","requestId":"…"}` to the LLM
     **without running the tool body**. The LLM is expected to end its
     turn with "I've requested approval for X" or similar.
3. The reviewer client then calls `POST /api/agent/approval/{requestId}`
   with `{"decision":"APPROVED|DENIED","reason":"…"}`.
4. The `ApprovalController` validates expiry and delegates to
   `approvalStore.resolve(requestId, decision)`.
5. Actually running the tool with the approved arguments is the caller's
   responsibility — typically a follow-up chat message from the reviewer
   UI that re-triggers the tool path.

### `ApprovalStore` backends

| Backend | Active when | Survives restart |
|---------|-------------|------------------|
| Redis (`RedisApprovalStore`) | a Redis bean is present | ✅ |
| In-memory (`InMemoryApprovalStore`) | embedded mode | ❌ |

The in-memory store is wired by `EmbeddedProfileAutoConfiguration` — same
`ApprovalStore` interface, same lifecycle (`savePending` / `getPending` /
`resolve` / `isExpired`), TTL enforced by the request's `expiresAt`
instant rather than by Redis' built-in expiry. This is what the test
suite below uses.

### Other moving parts

- **`HitlCoordinator`** — wrapper around `ApprovalStore` that enforces
  `agent.hitl.auto-deny-on-expiry` and `agent.hitl.require-reason-on-deny`.
- **`ApprovalController`** — exposes `POST /api/agent/approval/{id}`. Returns
  `200` on success, `410 Gone` when the request is unknown or already
  expired.
- **`agent.hitl.enabled`** must be `true` (it is by default in the
  `application.yml` here) for the coordinator + controller to wire.

---

## What this example demonstrates

`MoneyTransferTool` ([source](src/main/java/ai/gargantua/example/toolapproval/tools/MoneyTransferTool.java))
exposes two `@AgentTool` methods deliberately chosen to compare:

| Method | `@RequiresApproval` | What it proves |
|--------|--------------------|----------------|
| `getBalance(account)` | ✗ (control) | Identical wiring without the annotation — `ToolDefinition` carries no approval metadata. |
| `transfer(from, to, amount)` | ✓ — `message=…, showParameters={from,to,amount}, dangerous=true` | The annotation flows into `ToolDefinition` and is what the streaming controller branches on. |

Both tools are exposed to the LLM via `allowed-tools`. The `transfer`
method also mutates an in-memory `Map<String, Long>` so the test layer
can assert behaviour pre- and post-transfer.

---

## What the test suite proves

[`ToolApprovalApplicationTest.java`](src/test/java/ai/gargantua/example/toolapproval/ToolApprovalApplicationTest.java)
boots the full Spring context in the `embedded` profile and pins every
externally observable guarantee:

| Test method                              | Pins                                                                                          |
|------------------------------------------|-----------------------------------------------------------------------------------------------|
| `transferMetadataIsRegistered`           | `ToolDefinition.requiresApproval` / `approvalMessage` / `dangerous` reflect the annotation.    |
| `getBalanceCarriesNoApprovalMetadata`    | A method without `@RequiresApproval` has the default flags (false / empty / false).            |
| `approvalStoreRoundTrip`                 | `savePending` → `getPending` returns the request; `resolve` removes it.                       |
| `approvalStoreExpiry`                    | Unknown id is expired; a request with past `expiresAt` is expired and is lazily evicted.       |
| `restResolvesPendingRequest`             | `POST /api/agent/approval/{id}` returns 200 + the decision, and removes the pending entry.    |
| `restRejectsUnknownRequestId`            | Unknown id returns `410 Gone` (the framework's `ApprovalExpiredException` mapper).             |
| `registryDoesNotGateApproval`            | Documents that `ToolRegistry.executeTool` runs the tool body regardless of `requiresApproval` — gating happens in the streaming controller. |

The streaming SSE pause flow itself is exercised live by `docker compose
up` (see below); the unit-test layer doesn't drive an actual LLM.

---

## Run it

### A. Verify the feature (no Docker, no LLM, no Internet)

```bash
cd agent-example-tool-approval
mvn -U test
```

Seven assertions, ~10 s build. No infrastructure.

### B. Watch the SSE pause live (Docker, fully-local Ollama)

```bash
cd agent-example-tool-approval
cp .env.example .env
docker compose up -d
```

Then connect to the stream and ask the agent to move money:

```bash
curl -N -X POST http://localhost:8080/api/agent/chat/stream \
  -H "Content-Type: application/json" \
  -H "X-User-Id: alice" -H "X-Session-Id: s1" \
  -d '{"message": "Transfer 100 cents from alice to bob"}'
```

You'll see an `approval_required` SSE event with a `requestId`. Resolve it:

```bash
curl -X POST http://localhost:8080/api/agent/approval/<requestId> \
  -H "Content-Type: application/json" \
  -d '{"decision": "APPROVED", "reason": "looks good"}'
```

### C. Embedded mode on the host (no Docker, optional LLM)

```bash
SPRING_PROFILES_ACTIVE=embedded mvn spring-boot:run
```

---

## Open gaps in the framework (relevant to this annotation)

Discovered while building this example. Documented here so the example's
README stays honest; not blocking the example itself.

1. **The non-streaming `POST /api/agent/chat` endpoint does NOT honour
   `@RequiresApproval`.** `DefaultOrchestratorEngine.runToolCallingLoop`
   calls `ToolRegistry.executeTool(...)` directly, which runs the tool
   body regardless of `requiresApproval`. Only the SSE controller has
   the gate. A `dangerous`-flagged tool will silently execute when
   invoked via the non-streaming path.
2. **`showParameters` is declared but never applied.** The
   `approval_required` SSE event embeds the full `arguments` JSON; the
   `showParameters` subset is ignored. Currently the field is advisory
   documentation only.
3. **`ToolCacheAdminController` parses Redis directly** (noted in the
   tool-cache example's README too) — same pattern would arise here if
   anyone built an admin endpoint over `ApprovalStore`.

These will likely be addressed in a future framework release; the
example is correct against the current implementation.

---

## What this example deliberately does NOT show

| Concern                                | Where to look                                                |
|----------------------------------------|--------------------------------------------------------------|
| `@AgentTool` registration mechanics    | [`agent-example-tool-basics`](../agent-example-tool-basics/) |
| Transparent retries on flaky tools     | [`agent-example-tool-retry`](../agent-example-tool-retry/)   |
| Cached tool results                    | [`agent-example-tool-cache`](../agent-example-tool-cache/)   |
| Role-based access control              | `agent-example-tool-rbac` (planned)                          |
