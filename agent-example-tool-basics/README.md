# `@AgentTool` Basics — Gargantua feature example

The smallest possible Gargantua example. **One annotation, one tool class, one
skill, zero network calls.** Built to demonstrate exactly what `@AgentTool`
does — nothing more, nothing less — and to be fully verifiable with
`mvn test` (no Docker, no API keys, no Internet).

> Looking for a richer end-to-end demo? See
> [`agent-example-weather`](../agent-example-weather/) (real HTTP integration)
> or [`agent-example-cookbook`](../agent-example-cookbook/) (all annotation
> combos). This module deliberately covers only the `@AgentTool` annotation
> itself — caching, retries, approvals, RBAC, RAG, memory, etc. each have
> their own dedicated example.

---

## The feature: what `@AgentTool` is

`@AgentTool` is the annotation that turns a plain Java method on a Spring
bean into a tool the agent can call. Source:
[`AgentTool.java`](https://github.com/GiskardB/gargantua/blob/main/agent-core/src/main/java/ai/gargantua/core/tool/AgentTool.java).

```java
@Target(METHOD)
@Retention(RUNTIME)
public @interface AgentTool {
    String  name()           default "";    // override; defaults to method name
    String  description();                  // shown to the LLM — REQUIRED
    boolean parallelizable() default true;  // can run alongside other tool calls
}
```

### How registration works

At boot, the framework's `ToolRegistry` (`ai.gargantua.autoconfigure.ToolRegistry`)
runs a `@PostConstruct` scan over **every Spring bean** in the application
context. For each public method annotated with `@AgentTool` it:

1. Resolves the tool name — `annotation.name()` if non-blank, otherwise
   the Java method name.
2. Builds a `ToolDefinition` record holding `name`, `description`,
   `parallelizable`, plus flags merged in from sibling annotations
   (`@RequiresApproval`, `@CacheableToolResult`).
3. Caches the bean + `Method` + sibling annotations in a `ToolInvocation`
   record so the hot path is pure field reads.

### How invocation works

When the LLM emits a tool call, the orchestrator hands the registry the
**tool name** and a JSON arguments object. `ToolRegistry.executeTool(...)`:

1. Looks up the cached invocation.
2. Parses the JSON args into a `Map<String, String>` (all values arrive as
   strings because the tool spec sent to the LLM declares every parameter
   as `string` — see `JsonObjectSchema.addStringProperty(...)` in
   `ToolRegistry#buildToolSpecifications`).
3. Converts each value to the method parameter type (primitives, `String`,
   or via Jackson for complex types).
4. Reflectively invokes the method.
5. Serialises the return value to JSON (a primitive becomes a bare number,
   a record becomes an object, a `String` is returned verbatim).
6. Wraps any thrown `RuntimeException` in `{"error":"..."}` so the LLM gets a
   recoverable signal rather than aborting the turn (framework v1.2.3+).

That entire path — discovery, lookup, JSON arg parsing, reflective call,
JSON serialisation, error wrapping — is what the test suite below pins.

---

## What this example demonstrates

A single `CalculatorTool` class with three `@AgentTool` methods, each one
chosen to exercise a different aspect of the annotation:

| Java method                  | Tool name (LLM sees) | Description excerpt              | `parallelizable` | Why it's here                                                       |
|------------------------------|----------------------|----------------------------------|------------------|---------------------------------------------------------------------|
| `add(int, int)`              | `add`                | "Adds two integers"              | `true` (default) | Bare minimum — only `description` is set; primitive return.         |
| `multiply(int, int)`         | `multiply`           | "structured result"              | `true` (default) | Returns a `MultiplyResult` record → shows JSON object serialisation. |
| `power(int base, int exp)`   | **`pow`**            | "Raises an integer base"         | `false`          | Sets `name="pow"` → demonstrates name override; sets `parallelizable=false`. |

That's it. No HTTP, no DB, no caches. If you understand this file you
understand `@AgentTool`:

[`CalculatorTool.java`](src/main/java/ai/gargantua/example/toolbasics/tools/CalculatorTool.java).

---

## What the test suite proves

[`ToolBasicsApplicationTest.java`](src/test/java/ai/gargantua/example/toolbasics/ToolBasicsApplicationTest.java)
boots the full Spring context in the `embedded` profile (no Mongo, no Redis)
and asserts every guarantee `@AgentTool` makes:

| Test method                                | Pins                                                                                          |
|--------------------------------------------|-----------------------------------------------------------------------------------------------|
| `contextLoads`                             | The auto-configuration boots without infra dependencies.                                      |
| `calculatorToolBeanIsRegistered`           | Discovery is just standard Spring component scanning.                                         |
| `allThreeToolsAreRegisteredByLlmName`      | Three tools registered; `pow` is present and `power` (the Java name) is not.                   |
| `descriptionsAreWiredThrough`              | `ToolDefinition.description()` carries the annotation value verbatim.                          |
| `parallelizableFlagIsWiredThrough`         | `parallelizable=false` reaches the registry; default `true` is preserved.                      |
| `executeAddReturnsPrimitiveJson`           | A primitive `int` return is JSON-encoded as `"5"`.                                            |
| `executeMultiplyReturnsStructuredJson`     | A record return is JSON-encoded with field names — exactly what the LLM consumes.             |
| `executePowUsesAnnotationNameNotMethodName`| The orchestrator dispatches on the annotation name, not the method name.                       |
| `unknownToolReturnsErrorJson`              | Missing tool → `{"error":"Tool not found: …"}` instead of an exception.                       |
| `runtimeExceptionIsCaughtAndReturned`      | A thrown `IllegalArgumentException` becomes a structured JSON error payload.                   |
| `directInvocationStillWorks`               | The Java methods remain pure functions — `@AgentTool` adds nothing at the call site.          |

This is what "verifiable example" means here: every behaviour the README
claims is asserted in code, and the assertions run in seconds without
external dependencies.

---

## Run it

### A. Verify the feature (no Docker, no LLM, no Internet)

```bash
cd agent-example-tool-basics
mvn -U test
```

Eleven assertions, ~10 s build, no infrastructure. **This is the
recommended way to confirm the example works.**

### B. Chat with the agent (Docker, fully-local Ollama)

```bash
cd agent-example-tool-basics
cp .env.example .env       # defaults to local Ollama — no key needed
docker compose up -d       # mongo + redis + ollama + the agent
```

First boot pulls ~4 GB of Ollama models; subsequent ups are fast. Then:

```bash
curl -X POST http://localhost:8080/api/agent/chat \
  -H "Content-Type: application/json" \
  -H "X-User-Id: alice" -H "X-Session-Id: s1" \
  -d '{"message": "What is 17 plus 25?"}'

curl -X POST http://localhost:8080/api/agent/chat \
  -H "Content-Type: application/json" \
  -H "X-User-Id: alice" -H "X-Session-Id: s1" \
  -d '{"message": "Compute 2 to the power of 10."}'
```

Or open <http://localhost:8080/chat>.

### C. Embedded mode on the host (no Docker, optional LLM)

```bash
export LLM_PRIMARY_PROVIDER=openai
export LLM_PRIMARY_MODEL=gpt-4o-mini
export LLM_PRIMARY_API_KEY=sk-...
SPRING_PROFILES_ACTIVE=embedded mvn spring-boot:run
```

Storage runs in-memory; the chat path still needs *some* reachable LLM.

---

## Endpoints (chat path)

| What            | URL                                            |
|-----------------|------------------------------------------------|
| Chat web UI     | <http://localhost:8080/chat>                   |
| Swagger UI      | <http://localhost:8080/swagger-ui>             |
| Agent Card (A2A)| <http://localhost:8080/.well-known/agent.json> |
| Health          | <http://localhost:8080/actuator/health>        |

---

## What this example deliberately does NOT show

| Concern                                | Where to look                                                |
|----------------------------------------|--------------------------------------------------------------|
| Tool retries on failure                | `agent-example-tool-retry` (planned)                         |
| Cached tool results                    | `agent-example-tool-cache` (planned)                         |
| Human approval before execution        | `agent-example-tool-approval` (planned)                      |
| Role-based access control              | `agent-example-tool-rbac` (planned)                          |
| Real external HTTP calls               | [`agent-example-weather`](../agent-example-weather/)         |
| Multiple skills + structured output    | [`agent-example-cookbook`](../agent-example-cookbook/)       |

Each of those features is its own annotation or its own configuration block.
The point of this module is that `@AgentTool` by itself, with nothing else,
is enough to get a method into the agent's hands.
