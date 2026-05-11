# `@RequiresRole` — Gargantua feature example

Per-feature demo of `@RequiresRole`: the annotation that gates a tool
method by the caller's roles. One tool class, one skill, and an
integration test that drives the registry-level RBAC gate directly with
synthetic `SecurityContext`s — no LLM, no Mongo, no Redis.

> Sibling examples:
> [`agent-example-tool-basics`](../agent-example-tool-basics/),
> [`agent-example-tool-retry`](../agent-example-tool-retry/),
> [`agent-example-tool-cache`](../agent-example-tool-cache/),
> [`agent-example-tool-approval`](../agent-example-tool-approval/).

---

## The feature: what `@RequiresRole` does

`@RequiresRole` is a method-level annotation that lists one or more roles
the caller must possess for the tool to run. Source:
[`RequiresRole.java`](https://github.com/GiskardB/gargantua/blob/main/agent-core/src/main/java/ai/gargantua/core/security/RequiresRole.java).

```java
@Target(METHOD)
@Retention(RUNTIME)
public @interface RequiresRole {
    String[] value();   // user must have AT LEAST ONE of these
}
```

### Where the gate fires

Unlike `@RequiresApproval` (which is checked in the streaming chat
controller), the RBAC gate lives in **`ToolRegistry.executeTool(...)` itself**.
That means:

- The gate works for both chat paths (streaming and non-streaming).
- Any direct caller of `executeTool` (your own code, tests, integration
  glue) also gets the gate for free.
- The gate fires **before** `@CacheableToolResult` and `@ToolRetry`, so a
  denied call never reads the cache and never retries.

### How the check works

```java
RequiresRole annotation = invocation.requiresRole();   // cached at scan-time
if (annotation != null && annotation.value().length > 0) {
    if (securityContext == null) {
        return errorJson("Access denied: no security context for role-restricted tool '" + toolName + "'");
    }
    if (!securityContext.hasAnyRole(annotation.value())) {
        return errorJson("Access denied: user '" + securityContext.userId()
                       + "' lacks required role(s) " + List.of(annotation.value())
                       + " for tool '" + toolName + "'");
    }
}
```

A denied call returns a structured `{"error":"Access denied: …"}` payload
the LLM can read. The tool body is never invoked.

### `super-admin` wildcard

`SecurityContext#hasRole(role)` returns `true` if the user has either the
specific role **or** `super-admin`:

```java
public boolean hasRole(String role) {
    return roles.contains(role) || roles.contains("super-admin");
}
```

So `super-admin` is an implicit wildcard granting any required role. Use
sparingly — it bypasses every `@RequiresRole` annotation in the system.

### Where does the SecurityContext come from?

In production, the framework's `SecurityContextFilter` builds the context
from HTTP headers propagated by the API gateway:

| Header           | Field             |
|------------------|-------------------|
| `X-User-Id`      | `userId`          |
| `X-Tenant-Id`    | `tenantId`        |
| `X-User-Roles`   | `roles` (CSV)     |

If headers are missing the request runs as an anonymous user with an
empty role set — which fails every `@RequiresRole` gate. **Fail closed.**

---

## What this example demonstrates

`AdminTool` ([source](src/main/java/ai/gargantua/example/toolrbac/tools/AdminTool.java))
exposes three `@AgentTool` methods, each isolating one aspect of the
annotation. Every method increments a counter on every actual invocation
so tests can prove a denied call never reaches the body.

| Method | `@RequiresRole` configuration | What it proves |
|--------|------------------------------|----------------|
| `viewAuditLog()` | `@RequiresRole("auditor")` | Single-role gate. Allow with role; deny without context or without the role. |
| `deleteUser(userId)` | `@RequiresRole({"admin", "owner"})` | Any-of semantics: holding either role is enough. |
| `publicInfo()` | (none) | Control case: no annotation, no gate; anonymous caller works. |

---

## What the test suite proves

[`ToolRbacApplicationTest.java`](src/test/java/ai/gargantua/example/toolrbac/ToolRbacApplicationTest.java)
boots the full Spring context in the `embedded` profile and pins every
externally observable guarantee of the annotation:

| Test method                              | Pins                                                                                          |
|------------------------------------------|-----------------------------------------------------------------------------------------------|
| `denyWithoutSecurityContext`             | No context + gated tool → `{"error":"... no security context ..."}`, body never runs.        |
| `denyWithWrongRole`                      | Context present, wrong role → deny, error mentions the required role.                         |
| `allowWithCorrectRole`                   | Required role present → tool body runs, counter increments.                                   |
| `allowWithAnyOfTheListedRoles`           | Multi-role any-of: one of the listed roles is enough.                                         |
| `denyWhenNoneOfTheRolesMatch`            | Multi-role: user with none of them is denied.                                                 |
| `superAdminBypassesEveryGate`            | `super-admin` is a wildcard granting any required role.                                       |
| `unannotatedToolRunsAnonymously`         | A tool without `@RequiresRole` is invocable even with no `SecurityContext`.                  |
| `deniedCallsLeaveCounterUntouched`       | Interleaved denied/allowed/denied calls move the counter only on the allowed one.             |

---

## Run it

### A. Verify the feature (no Docker, no LLM, no Internet)

```bash
cd agent-example-tool-rbac
mvn -U test
```

Eight assertions, ~10 s build. No infrastructure.

### B. Drive the gate from the chat path (Docker, fully-local Ollama)

```bash
cd agent-example-tool-rbac
cp .env.example .env
docker compose up -d
```

Then watch the gate fire / pass depending on the `X-User-Roles` header:

```bash
# Anonymous — viewAuditLog is denied
curl -X POST http://localhost:8080/api/agent/chat \
  -H 'Content-Type: application/json' \
  -H 'X-User-Id: alice' -H 'X-Session-Id: s1' \
  -d '{"message": "show me the audit log"}'

# With auditor role — allowed
curl -X POST http://localhost:8080/api/agent/chat \
  -H 'Content-Type: application/json' \
  -H 'X-User-Id: alice' -H 'X-Session-Id: s1' \
  -H 'X-User-Roles: auditor' \
  -d '{"message": "show me the audit log"}'

# super-admin — bypasses any gate
curl -X POST http://localhost:8080/api/agent/chat \
  -H 'Content-Type: application/json' \
  -H 'X-User-Id: root' -H 'X-Session-Id: s2' \
  -H 'X-User-Roles: super-admin' \
  -d '{"message": "delete user bob"}'
```

### C. Embedded mode on the host (no Docker, optional LLM)

```bash
SPRING_PROFILES_ACTIVE=embedded mvn spring-boot:run
```

---

## What this example deliberately does NOT show

| Concern                                | Where to look                                                |
|----------------------------------------|--------------------------------------------------------------|
| `@AgentTool` registration mechanics    | [`agent-example-tool-basics`](../agent-example-tool-basics/) |
| Retries / caching / approval flows     | sibling tool-* examples                                       |
| Tenant isolation                       | currently expressed in `SecurityContext.tenantId` but no annotation level enforcement |

The point of this module is the RBAC contract in isolation: every branch
of `@RequiresRole` (no context, wrong role, right role, any-of,
wildcard, no annotation) has a corresponding test method that pins it.
