# LLM Routing Rules — Gargantua feature example

Per-feature demo of the framework's **routing-rule / model-pool
grammar**: declarative rules in `application.yml` pick which LLM model
alias serves each request, with operators, combinators, time/day
matchers and a deterministic priority order. The whole grammar is
pinned end-to-end by direct calls to the autowired `LlmRouter` — no
LLM is invoked.

---

## The feature

`LlmRouter` consults `agent.llm.routing-rules` (a list of
`AgentProperties.RoutingRule`) on every request, sorts them by
**priority ascending** (lowest wins) and returns the first match. If no
rule matches, the **primary alias** (`agent.llm.primary-alias`) is
returned. Disabled rules are still listed in the trace but are never
selected.

Each rule has:

| Field          | Purpose                                                           |
|----------------|-------------------------------------------------------------------|
| `name`         | Unique identifier (used by the trace, admin endpoints, metrics).  |
| `priority`     | Integer, ascending wins. Use widely-spaced numbers (10, 20, 30…). |
| `enabled`      | When `false`, the rule appears in the trace with `matched=false`. |
| `condition`    | A `Map<String, Object>` evaluated by `RoutingRuleEvaluator`.       |
| `target-model` | The model alias returned when this rule matches.                  |
| `description`  | Free-form, surfaced by admin endpoints.                           |

The condition map is an **implicit AND** over its entries: every entry
must match. Inside the map, the supported keys are:

| Key                                  | Spec shape                                                                                                  |
|--------------------------------------|-------------------------------------------------------------------------------------------------------------|
| `skill`, `domain`, `user-tier`       | Scalar, or `{operator: EQ \| IN \| NOT_IN, value/values: …}`                                                  |
| `input-length`, `estimated-tokens`   | Scalar (EQ), or `{operator: GT \| GTE \| LT \| LTE \| EQ, value: N}`                                          |
| `min-tokens` (legacy)                | Scalar — implicit GTE on `estimated-tokens`.                                                                |
| `time-window`                        | `{from: "HH:MM", to: "HH:MM"}` — supports cross-midnight (22:00→06:00).                                     |
| `day-of-week`                        | `{days: [SATURDAY, SUNDAY, …]}` — full or 3-letter form.                                                    |
| `random-sampling`                    | `{percentage: 0–100}` — `100` always matches, `0` never matches.                                            |
| `input-contains`                     | `{patterns: [TODO, FIXME, …]}` — case-insensitive substring match against `userMessage`.                    |
| `attribute-match`                    | `{key: x-priority, operator: EQ \| CONTAINS \| REGEX, value: "…"}` — runs against `LlmRoutingContext.attributes()`. |
| `AND` / `OR`                         | List of nested condition maps.                                                                              |
| any other key                        | Falls back to exact-string match on `attributes().get(key)` (legacy shorthand).                             |

> **Spring Boot 4 binding note.** YAML lists nested inside a
> `Map<String,Object>` are bound as **indexed Maps** (`{0=a, 1=b}`) by
> `@ConfigurationProperties`. v1.2.11+ tolerates both shapes for every
> list-valued field (`values`, `days`, `patterns`, `AND`, `OR`), so the
> obvious YAML form just works. Use **v1.2.11 or later** for this
> example — older releases silently fail to match any rule that uses a
> list in its condition.

---

## What this example ships

`application.yml` declares **13 routing rules**, each one designed to
land on a specific test by name:

| Priority | Rule name                       | Demonstrates                                              | Target model         |
|---------:|---------------------------------|-----------------------------------------------------------|----------------------|
| 5        | `disabled-coding-rule`          | `enabled: false` → listed in trace but skipped            | (never selected)     |
| 10       | `coding-skill-eq`               | String `EQ` operator                                      | `model-fast`         |
| 15       | `medical-or-legal-domain-in`    | String `IN` operator                                      | `model-careful`      |
| 20       | `niche-domain-not-in`           | String `NOT_IN` operator                                  | `model-specialty`    |
| 25       | `long-input-gte`                | Numeric `GTE` on `input-length`                            | `model-cheap`        |
| 30       | `min-tokens-legacy`             | Legacy `min-tokens` shorthand (implicit GTE)              | `model-cheap-legacy` |
| 35       | `premium-and-todo`              | `AND` combinator (tier + `input-contains`)                | `model-premium-code` |
| 40       | `night-hours`                   | `time-window` crossing midnight                           | `model-night`        |
| 45       | `weekend-day`                   | `day-of-week`                                             | `model-weekend`      |
| 50       | `canary-sampled-100`            | `random-sampling: {percentage: 100}` AND-ed with a skill  | `model-canary`       |
| 55       | `priority-header-regex`         | `attribute-match` with `REGEX`                            | `model-priority`     |
| 60       | `or-skill-or-domain`            | `OR` combinator                                           | `model-or-target`    |
| 65       | `legacy-tenant-eq`              | Unknown key → attribute-equality fallback                 | `model-tenant-gold`  |

A trivial `default-skill` and a placeholder `NoopTool` keep the agent
context bootable; the routing tests bypass routing entirely by calling
`LlmRouter` directly.

---

## What the test suite proves

[`LlmRoutingApplicationTest.java`](src/test/java/ai/gargantua/example/llmrouting/LlmRoutingApplicationTest.java)
boots the Spring context in the `embedded` profile and asserts both
`selectedAlias` and `matchedRule` for each scenario:

| Test method                                          | Pins                                                                                                       |
|------------------------------------------------------|------------------------------------------------------------------------------------------------------------|
| `noMatchFallsBackToPrimaryAlias`                     | No rule matches → `default` (primary alias), `matchedRule == null`.                                         |
| `emptyRulesListReturnsPrimaryAlias`                  | Empty `routing-rules` short-circuit (separate `LlmRouter` instance, empty trace).                          |
| `skillEqMatchesActiveRule`                           | `skill: {operator: EQ, value: coding-skill}` lands on `coding-skill-eq`.                                    |
| `disabledRuleIsListedButSkipped`                     | Trace entry for `disabled-coding-rule` reports `enabled=false, matched=false`; the next-priority rule wins. |
| `domainInOperatorMatches`                            | `IN` operator over a domain list.                                                                          |
| `domainNotInOperatorMatches`                         | `NOT_IN` operator excludes common domains.                                                                 |
| `inputLengthGteMatches`                              | Numeric `GTE` on `input-length`.                                                                           |
| `legacyMinTokensGteMatches`                          | Legacy `min-tokens` scalar (GTE on `estimated-tokens`).                                                    |
| `andCombinatorMatchesPremiumWithTodo`                | `AND` requires both `user-tier=premium` and `input-contains: [TODO, FIXME]`.                                |
| `orCombinatorMatchesFirstArm`                        | `OR` matches via the first arm (skill).                                                                    |
| `timeWindowCrossesMidnight`                          | 23:30 ∈ window `22:00 → 06:00` (cross-midnight wrap).                                                       |
| `dayOfWeekSaturdayMatches`                           | `day-of-week: [SATURDAY, SUNDAY]` matches a Saturday request.                                              |
| `randomSampling100AlwaysMatchesForCanarySkill`       | `random-sampling: {percentage: 100}` AND-ed with a skill gate → always picks the canary rule.              |
| `attributeRegexMatches`                              | `attribute-match` with `REGEX` matches `x-priority=urgent` against `^(high\|urgent)$`.                       |
| `legacyUnknownKeyAttributeFallback`                  | Unknown condition key (`tenant-id`) falls back to attribute equality.                                      |
| `priorityLowerWins`                                  | When two rules match, the lower-priority wins; trace still shows BOTH as `matched=true`.                   |
| `allRulesAreLoaded`                                  | 13 rules loaded from YAML, `findRule` lookup works.                                                        |

---

## Run it

### A. Verify the feature (no Docker, no LLM, no Internet)

```bash
cd agent-example-llm-routing
mvn -U test
```

17 assertions, ~10 s build, no infrastructure.

### B. Live (Docker, fully-local Ollama)

```bash
cd agent-example-llm-routing
cp .env.example .env
docker compose up -d
```

Each chat request goes through `LlmRouter` before the LLM call; logs
under `ai.gargantua.autoconfigure.LlmRouter` show the selected alias
and the matched rule name. Use the admin endpoint
`GET /api/admin/llm/routing/rules` (and the simulate endpoint
documented in `llm-configuration.md`) to inspect rule evaluation
against arbitrary contexts at runtime.

### C. Embedded mode on the host

```bash
SPRING_PROFILES_ACTIVE=embedded mvn spring-boot:run
```

---

## What this example deliberately does NOT show

| Concern                                       | Where to look                                                                                |
|-----------------------------------------------|-----------------------------------------------------------------------------------------------|
| Per-rule metrics (`agent.llm.routing.rule.matched` counter) | Live in Prometheus; documented in `llm-configuration.md`.                                     |
| Cost-aware routing (route by per-model cost)  | Cost data lives in `cost-tracking` — separate per-feature example.                            |
| Multi-provider model pool resolution          | `LlmRegistry` / `LlmClient` resolution is upstream of the router and out of scope here.       |
| Live admin simulate endpoint                  | `POST /api/admin/llm/routing/simulate` exists; the test bypasses it by calling `evaluateAll`. |
