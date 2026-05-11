# Routing strategy — Gargantua feature example

Per-feature demo of the **skill-routing layer**: how the framework
picks which skill answers an incoming request. Three strategies are
supported via `agent.routing.strategy`:

| Strategy   | What it does                                                                                                        | Latency                | Needs an LLM? |
|------------|----------------------------------------------------------------------------------------------------------------------|------------------------|---------------|
| `semantic` | Cosine similarity between an in-process ONNX embedding of the user message and each skill's description.            | ~2–5 ms per query      | ❌ |
| `llm`      | Sends the user message + the active-skills catalog to the routing model, which replies with the chosen skill name.   | One round-trip to the routing model | ✅ |
| `hybrid` (default) | Tries `semantic` first; falls back to `llm` only when the best cosine score is below the configured threshold. | Best of both           | Sometimes |

When the strategy cannot reach a confident answer (semantic below
threshold, or LLM failure on the LLM path), the framework returns the
configured `agent.routing.fallback-skill` — the result is **never** an
exception, the tag on `RoutingResult.method()` still reflects the
strategy that ran. This example pins all of those guarantees.

---

## The feature

`SemanticRoutingService.route(userMessage, skills)` returns a
[`RoutingResult`](https://github.com/GiskardB/gargantua/blob/main/agent-core/src/main/java/ai/gargantua/core/orchestrator/RoutingResult.java)
with the chosen skill, the `RoutingMethod` (`SEMANTIC` / `LLM` /
`FORCED`), and a confidence score. The orchestrator drives this once per
request and then runs the rest of the pipeline (guardrails → memory →
LLM) against the chosen skill.

```yaml
agent:
  routing:
    strategy: semantic           # or 'llm' or 'hybrid'
    fallback-skill: default-skill
    semantic:
      threshold: 0.30            # cosine threshold (default 0.82 in the framework)
```

### `semantic` — embeddings only

Uses [all-MiniLM-L6-v2 quantised](https://huggingface.co/sentence-transformers/all-MiniLM-L6-v2)
loaded via LangChain4j's ONNX adapter. Embeddings are computed once per
skill description at boot (and re-indexed on skill reload). Each
incoming user message is embedded and compared with cosine similarity;
the highest-scoring skill above `threshold` wins. Below threshold the
service returns the configured `fallback-skill` — `method = SEMANTIC`
remains.

The framework's default threshold of `0.82` assumes long, highly
specific descriptions; this example deliberately drops it to `0.30`
because realistic short prompts vs. brief skill descriptions land in
the 0.4–0.7 range with `all-MiniLM-L6-v2`. **Tune the threshold to your
catalog.**

### `llm` — routing model classifies

Sends a classification prompt (`"You are a skill router..."`) plus the
active-skills catalog to whatever model is configured under
`agent.llm.routing-model.*`. The model must reply with just a skill
name; the service validates the reply against the known skill names and
falls back to `fallback-skill` if the answer is unknown or the call
fails (timeout, network, model error). Either way, `method = LLM`.

### `hybrid` — semantic with LLM fallback

The default. Best of both: most queries get the ~3 ms embedding path;
the LLM is only consulted on genuinely ambiguous messages. When the
semantic path succeeds, `method = SEMANTIC` and no LLM call is made;
when the LLM fallback kicks in, `method = LLM`.

---

## What this example demonstrates

Three skills with deliberately disjoint descriptions, plus a default
greeter for the fallback:

| Skill            | Description summary                                          | Example query                                                     |
|------------------|---------------------------------------------------------------|--------------------------------------------------------------------|
| `weather-skill`  | Weather, meteorology, forecasts                              | "What is the weather and temperature in Milan today?"              |
| `finance-skill`  | Stock prices, equities, earnings                             | "What is the current stock price of Apple and Microsoft?"          |
| `coding-skill`   | Software engineering, Python/Java/JS, debugging              | "I need help with software engineering — writing Python functions" |
| `default-skill`  | Greeter + fallback                                            | "hello"                                                            |

Plus a `NoopTool` `@AgentTool echo` so the SKILL.md linter accepts every
`allowed-tools` entry.

---

## What the test suite proves

[`RoutingStrategyApplicationTest.java`](src/test/java/ai/gargantua/example/routingstrategy/RoutingStrategyApplicationTest.java)
boots the full Spring context in the `embedded` profile and pins each
strategy by flipping `AgentProperties.getRouting().setStrategy(...)` at
runtime:

| Test method                                | Pins                                                                                                                  |
|--------------------------------------------|------------------------------------------------------------------------------------------------------------------------|
| `semanticRoutesWeatherQuery`               | strategy=`semantic` matches the weather query to `weather-skill` with `method = SEMANTIC` and confidence ≥ threshold.  |
| `semanticRoutesFinanceQuery`               | Same for the finance query.                                                                                            |
| `semanticRoutesCodingQuery`                | Same for the coding query.                                                                                             |
| `semanticBelowThresholdFallsBack`          | strategy=`semantic` + a forced threshold of 0.999 → returns `default-skill`, still `method = SEMANTIC`.                |
| `hybridAboveThresholdStaysSemantic`        | strategy=`hybrid` on a confident match stays at the embedding stage; no LLM call. `method = SEMANTIC`.                 |
| `llmStrategyGracefulFallback`              | strategy=`llm` with no reachable Ollama → returns `default-skill` without throwing. `method = LLM`.                    |
| `emptySkillsShortCircuit`                  | Empty skill list → `default-skill`, confidence 0.                                                                      |

---

## Run it

### A. Verify the feature (no Docker, no LLM, no Internet)

```bash
cd agent-example-routing-strategy
mvn -U test
```

Seven assertions, ~10 s build, no infrastructure. Boots an in-process
ONNX model (~2 s first call). The `llmStrategyGracefulFallback` test
relies on Ollama being unreachable — if you accidentally have one
running locally, the routing model will be queried and the test still
passes (just with a non-fallback result and the assertion adjusted in
your fork).

### B. Run live with all three strategies (Docker, fully-local Ollama)

```bash
cd agent-example-routing-strategy
cp .env.example .env
docker compose up -d
```

Then exercise each strategy by editing the `agent.routing.strategy`
property in `application.yml` and restarting, or hit the LLM-routing
admin endpoint to inspect routing decisions:

```bash
curl -X POST http://localhost:8080/api/agent/chat \
  -H 'Content-Type: application/json' \
  -H 'X-User-Id: alice' -H 'X-Session-Id: s1' \
  -d '{"message": "Will it rain in Milan?"}'
```

### C. Embedded mode on the host

```bash
SPRING_PROFILES_ACTIVE=embedded mvn spring-boot:run
```

---

## What this example deliberately does NOT show

| Concern                                       | Where to look                                                                |
|-----------------------------------------------|------------------------------------------------------------------------------|
| Per-request `forceSkill` (skip routing)       | `AgentRequest.builder().forceSkill(...)` — used by `FlowExecutor` to bypass routing during a flow. See [`agent-example-agents-flow`](../agent-example-agents-flow/). |
| LLM **model-pool** routing rules (operator grammar) | Separate subsystem under `agent.llm.routing.rules.*`; covered by a future `agent-example-llm-routing` module. |
| Hot-reload of skills (re-indexing embeddings) | `agent.skill.hot-reload: true`; see [`agent-example-skill-filesystem`](../agent-example-skill-filesystem/) for the SKILL.md side. |
