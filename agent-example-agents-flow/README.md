# `@AgentsFlow` — Gargantua feature example

Per-feature demo of `@AgentsFlow`: the DSL that wires multiple skills
into a multi-step pipeline. Four annotated methods cover every step
type — sequential, sequential-with-instruction, loop, and a
fan-out/fan-in (parallel) block. The discovery + metadata contract is
fully verifiable with `mvn test`; live execution needs an LLM (use the
docker-compose recipe).

> Sibling examples: the `agent-example-tool-*` and `agent-example-skill-*`
> modules.

---

## The feature

`@AgentsFlow` is a method-level annotation on a Spring bean. The method
receives a mutable [`FlowDefinition`](https://github.com/GiskardB/gargantua/blob/main/agent-core/src/main/java/ai/gargantua/core/flow/FlowDefinition.java)
that you populate with calls to `.step(...)`, `.loop(...)`, `.parallel(...)`.
At boot, [`FlowRegistry`](https://github.com/GiskardB/gargantua/blob/main/agent-engine/src/main/java/ai/gargantua/autoconfigure/FlowRegistry.java)
scans every Spring bean for methods carrying the annotation, invokes
each one with a fresh `FlowDefinition`, and stores the populated
definition by `name()`.

```java
@AgentsFlow(name = "code-review", description = "Plan, code, then review")
public void codeReviewFlow(FlowDefinition flow) {
    flow.step("planner")
        .step("coder")
        .step("reviewer");
}
```

### Step types

| Builder call                   | Resulting `FlowStep.type()` | Executor behaviour                                                                 |
|--------------------------------|------------------------------|------------------------------------------------------------------------------------|
| `.step(name)`                  | `SEQUENTIAL`                 | Run once; output becomes the next step's input.                                    |
| `.step(name, instruction)`     | `SEQUENTIAL`                 | Same, but `instruction` is prepended to the step input.                            |
| `.loop(name, maxIterations)`   | `LOOP`                       | Re-run the skill up to `maxIterations`; exit early when output contains `[DONE]` or `[SATISFIED]`. |
| `.parallel(a, b, c)`           | `PARALLEL` × N               | Each name becomes its own `PARALLEL` step. The executor coalesces adjacent `PARALLEL` steps and runs them on virtual threads; the next non-parallel step receives all outputs concatenated. |

### Execution path

`FlowExecutor.execute(flow, input, userId, sessionId, securityContext)`
walks the steps. Each step builds an `AgentRequest` with `forceSkill =
step.skillName()` and submits it to the framework's `OrchestratorEngine`
— so every step still goes through guardrails, memory, the LLM router
override, and the audit trail. The result is a
[`FlowResult`](https://github.com/GiskardB/gargantua/blob/main/agent-core/src/main/java/ai/gargantua/core/flow/FlowResult.java)
that surfaces every step's input + output + duration.

### REST surface

`FlowController` exposes:

| Method | URL                                | Purpose                                                  |
|--------|------------------------------------|----------------------------------------------------------|
| `GET`  | `/api/flows`                       | List every registered flow with its step skill names.    |
| `POST` | `/api/flows/{name}/start`          | Run a flow with body `{"input": "..."}` (or `"message"`). |

A flow whose skills do not exist as live `SKILL.md` / `@AgentSkill`
classes will still appear in the listing but will fail at execution
time — `FlowRegistry` does not validate the skill names.

---

## What this example ships

[`MyFlows`](src/main/java/ai/gargantua/example/agentsflow/flows/MyFlows.java)
defines four flows, each chosen to isolate one branch of the DSL:

| Flow name                  | Step shape                                                    | What it pins |
|----------------------------|---------------------------------------------------------------|-------------|
| `code-review`              | `planner → coder → reviewer`                                  | Pure sequential pipeline.                              |
| `translate-and-summarize`  | `translator → summariser` with per-step instructions          | Sequential with `.step(name, instruction)`.            |
| `refine-until-done`        | `loop("editor", 5)`                                           | LOOP step with `maxIterations` budget.                 |
| `map-reduce`               | `planner → parallel(analyst-sentiment, analyst-fundamentals) → reducer` | Fan-out/fan-in with PARALLEL block.   |

The skill names are placeholders — the example does not ship matching
`SKILL.md` files because the test layer only exercises discovery /
metadata, not live execution.

---

## What the test suite proves

[`AgentsFlowApplicationTest.java`](src/test/java/ai/gargantua/example/agentsflow/AgentsFlowApplicationTest.java)
boots the full Spring context in the `embedded` profile and pins:

| Test method                                | Pins                                                                                          |
|--------------------------------------------|-----------------------------------------------------------------------------------------------|
| `allFourFlowsDiscovered`                   | All four `@AgentsFlow` methods produce a `FlowDefinition` entry in `FlowRegistry.getAll()`.    |
| `getByNameOptional`                        | `FlowRegistry.get(known)` is present; unknown is empty.                                       |
| `nameAndDescriptionRoundTrip`              | `@AgentsFlow(name, description)` propagate verbatim to `FlowDefinition`.                      |
| `sequentialStepsRoundTrip`                 | `.step(name)` calls register in declared order as `SEQUENTIAL` with `instruction == null`.    |
| `sequentialStepInstructionPropagates`      | `.step(name, instruction)` carries the instruction on the step record.                        |
| `loopStepCarriesMaxIterations`             | `.loop(name, n)` registers a single `LOOP` step with `maxIterations == n`.                    |
| `parallelBlockExpandsToOneStepPerSkill`    | `.parallel(a, b)` expands into one `PARALLEL` step per skill name, in order.                  |
| `consecutiveParallelStepsAreContiguous`    | The `PARALLEL` block sits contiguously between the surrounding `SEQUENTIAL` steps.            |

Live execution is out of scope for `mvn test` (it would need an LLM per
step). See "Run it" below for the docker-compose recipe.

---

## Run it

### A. Verify the feature (no Docker, no LLM, no Internet)

```bash
cd agent-example-agents-flow
mvn -U test
```

Eight assertions, ~10 s build, no infrastructure.

### B. Execute a flow end-to-end (Docker, fully-local Ollama)

> A live run needs each referenced skill to exist as a real `SKILL.md`
> file or `@AgentSkill` class. This example ships placeholder skill
> names — pair it with a real skill source (e.g. by combining it with
> [`agent-example-skill-filesystem`](../agent-example-skill-filesystem/))
> if you want to actually run the flows. The recipes below assume those
> skills are available.

```bash
cd agent-example-agents-flow
cp .env.example .env
docker compose up -d

# List the registered flows
curl http://localhost:8080/api/flows

# Run a flow
curl -X POST http://localhost:8080/api/flows/code-review/start \
  -H 'Content-Type: application/json' \
  -H 'X-User-Id: alice' -H 'X-Session-Id: s1' \
  -d '{"input": "Write a fizzbuzz function in Python"}'
```

### C. Embedded mode on the host

```bash
SPRING_PROFILES_ACTIVE=embedded mvn spring-boot:run
```

---

## What this example deliberately does NOT show

| Concern                                | Where to look                                                                |
|----------------------------------------|------------------------------------------------------------------------------|
| Skills (the targets of each flow step) | [`agent-example-skill-annotation`](../agent-example-skill-annotation/) and [`agent-example-skill-filesystem`](../agent-example-skill-filesystem/) |
| Tool annotations                       | sibling `agent-example-tool-*` modules                                       |
| Live flow execution                    | docker-compose recipe above (needs real skills + LLM)                        |
| Custom `OrchestratorEngine` patterns   | The `langchain4j-agentic` dependency is on the classpath for advanced graph patterns; this example uses the built-in `FlowExecutor` so guardrails / memory / audit apply to every step. |
