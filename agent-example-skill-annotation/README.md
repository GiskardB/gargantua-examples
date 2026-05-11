# `@AgentSkill` — Gargantua feature example

Per-feature demo of `@AgentSkill`: the annotation that lets you define a
skill **inline in Java** rather than in a SKILL.md file. One annotated
class, two `@AgentTool` methods, one `PROMPT` field, and an integration
test that pins every observable guarantee — plus an honest regression
watch for the one framework gap that this example surfaced.

> Sibling examples: `agent-example-tool-*`.

---

## The feature: what `@AgentSkill` does

`@AgentSkill` is a class-level annotation that turns a Spring bean into
a skill the agent can route to. Source:
[`AgentSkill.java`](https://github.com/GiskardB/gargantua/blob/main/agent-core/src/main/java/ai/gargantua/core/skill/AgentSkill.java).

```java
@Target(TYPE)
@Retention(RUNTIME)
public @interface AgentSkill {
    String   name();
    String   description();
    String   version()         default "1.0.0";
    String   domain()          default "general";
    boolean  active()          default true;
    String[] allowedRoles()    default {};
    String   knowledgeBase()   default "";
    int      ragMaxResults()   default 5;
    double   ragMinScore()     default 0.3;
    double   temperature()     default -1;
    int      maxTokens()       default -1;
    String   outputSchema()    default "";
    String   preferredModel()  default "";
    String[] examples()        default {};
}
```

### The authoring contract

Three things must line up for a class to be picked up as a skill:

1. **`@AgentSkill`** on the class with `name` and `description`.
2. **`@Component`** (or another Spring stereotype) so the class becomes
   a managed bean — `AgentSkillProcessor` scans the application context,
   not the classpath.
3. **`public static final String PROMPT`** field on the class. The
   framework reads this at runtime via reflection. Javadoc is not retained
   in JVM bytecode, so the framework's convention is a string field. If
   the field is absent the discovered skill gets a generic fallback
   prompt ("You are a helpful assistant for the X skill.") which is
   almost never what you want.

Tools are **auto-detected**: every `@AgentTool` method on the class
becomes part of the skill's `allowedTools`. No need to list them again
in the annotation.

### Annotation vs. SKILL.md

Both authoring mechanisms can coexist in the same application — this
example demonstrates that by shipping the calculator as `@AgentSkill`
and the greeter as a classic SKILL.md.

If two skills happen to share the same `name` (one from `@AgentSkill`,
one from a SKILL.md), the SKILL.md takes precedence — file-based
authoring wins.

---

## What this example demonstrates

[`JavaCalculatorAgent`](src/main/java/ai/gargantua/example/skillannotation/skills/JavaCalculatorAgent.java)
is a single class that declares the whole skill:

| What it sets        | Annotation field / class member            |
|---------------------|---------------------------------------------|
| Skill name          | `@AgentSkill(name = "java-calculator")`     |
| Description         | `@AgentSkill(description = "…")`            |
| Domain              | `@AgentSkill(domain = "math")`              |
| Temperature override| `@AgentSkill(temperature = 0.0)`            |
| Discovery examples  | `@AgentSkill(examples = {…})`               |
| System prompt       | `public static final String PROMPT = "…"`   |
| Allowed tools       | `@AgentTool` on `add(int,int)` + `multiply(int,int)` — auto-detected |

A SKILL.md `default-skill` sits alongside it to handle greetings — the
example deliberately shows the two authoring mechanisms living together.

---

## What the test suite proves

[`SkillAnnotationApplicationTest.java`](src/test/java/ai/gargantua/example/skillannotation/SkillAnnotationApplicationTest.java)
boots the full Spring context in the `embedded` profile and asserts:

| Test method                                | Pins                                                                                          |
|--------------------------------------------|-----------------------------------------------------------------------------------------------|
| `calculatorAgentBeanIsRegistered`          | The `@AgentSkill` class is also a Spring bean (the `@Component` is what enables discovery).   |
| `agentSkillProcessorFoundOurSkill`         | `AgentSkillProcessor.getDiscoveredSkills()` returns exactly one skill.                        |
| `metaCarriesAnnotationValues`              | `SkillMeta` fields (`name`, `version`, `domain`, `description`, `active`) round-trip.         |
| `sourceIsAnnotation`                       | `SkillMeta.source()` is `SkillSource.ANNOTATION` (vs. `FILESYSTEM` for SKILL.md).             |
| `allowedToolsAreAutoDetected`              | `allowedTools` lists `add` and `multiply` — auto-detected from `@AgentTool` methods.          |
| `systemPromptFromPromptField`              | `systemPrompt` is the content of the `PROMPT` field, NOT the generic fallback.                |
| `examplesPropagate`                        | `@AgentSkill(examples = …)` propagates to `SkillCard.examples()`.                             |
| `temperatureOverridePropagates`            | `@AgentSkill(temperature = 0.0)` propagates to `SkillCard.temperature()`.                     |
| `skillRegistryDoesNotYetSeeAnnotatedSkill` | **Regression watch** for the open gap below — flip the assertion when the gap is fixed.       |

---

## Open gaps surfaced by this example

This example exposed **two** related gaps in how `@AgentSkill` plugs into
the rest of the framework. Both will be closed in a subsequent framework
release; the example tests pin the current behaviour and the workaround.

### Gap A — `AgentSkillProcessor` is not auto-configured

`AgentSkillProcessor` carries a `@Component` annotation but is NOT
registered by any of the framework's auto-configuration `@Bean`
factories listed in `META-INF/spring/…AutoConfiguration.imports`.
Component scan only covers the user's own packages by default, so the
processor never gets instantiated and `@AgentSkill` discovery silently
does nothing — no error, no log, nothing.

**Workaround (in this example):** the application class explicitly
imports the processor:

```java
@SpringBootApplication
@Import(AgentSkillProcessor.class)
public class SkillAnnotationApplication { … }
```

### Gap B — `SkillRegistry` does not see annotated skills

Even after the processor is instantiated, its
`getDiscoveredSkills()` list is never consumed by the `SkillRegistry`
composite (`FilesystemSkillRegistry` + `ClasspathSkillsJarRegistry`).
Net effect:

- `AgentSkillProcessor.getDiscoveredSkills()` returns the annotated skill
  with correct metadata. ✓
- `SkillRegistry.findMeta("java-calculator")` returns `Optional.empty()`. ✗
- The chat router cannot select an annotated skill — it's invisible to
  the routing layer.

The last test (`skillRegistryDoesNotYetSeeAnnotatedSkill`) pins this
"not yet" behaviour and will be flipped to a positive assertion once the
framework wires `AgentSkillProcessor` into the registry.

For now, if you need an annotation-defined skill to be **routable** in
production, ship it as a SKILL.md file alongside it or wait for the next
release.

---

## Run it

### A. Verify the feature (no Docker, no LLM, no Internet)

```bash
cd agent-example-skill-annotation
mvn -U test
```

Nine assertions, ~10 s build, no infrastructure.

### B. Chat with the agent (Docker, fully-local Ollama)

```bash
cd agent-example-skill-annotation
cp .env.example .env
docker compose up -d
```

> Note: because of the open gap above, the agent currently routes to
> `default-skill` (which is a SKILL.md and IS in the registry) for every
> request. The annotated skill is discovered at startup but not
> selectable yet. The chat path will become useful once the framework
> wiring is in place.

### C. Embedded mode on the host

```bash
SPRING_PROFILES_ACTIVE=embedded mvn spring-boot:run
```

---

## What this example deliberately does NOT show

| Concern                                | Where to look                                                |
|----------------------------------------|--------------------------------------------------------------|
| `@AgentTool` registration mechanics    | [`agent-example-tool-basics`](../agent-example-tool-basics/) |
| Tool retries / caching / approval / RBAC | sibling `agent-example-tool-*` modules                     |
| SKILL.md authoring path                | Every other example in this repo (default-skill et al.)      |
| Skills bundled in a JAR                | `agent-example-skill-jar` (planned)                          |
