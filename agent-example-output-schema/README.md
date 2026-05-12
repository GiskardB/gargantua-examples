# Schema-Validated Output — Gargantua feature example

Per-feature demo of the JSON-Schema **output guardrail**: skills that
declare a `metadata.output-schema` in SKILL.md have every LLM response
validated against the schema, with structured BLOCK details when it
doesn't conform. Plus a documentation-only note on the orchestrator's
**corrective-prompt auto-retry** (which needs a live LLM to exercise).

> Sibling: [`agent-example-skill-filesystem`](../agent-example-skill-filesystem/)
> for the SKILL.md authoring path in general.

---

## The feature

Three pieces work together:

1. **`metadata.output-schema`** in SKILL.md frontmatter (a YAML literal
   block scalar containing JSON Schema Draft-07). The parser sets
   `SkillMeta.hasSchema = true` and stores the verbatim schema text in
   `SkillCard.outputSchema()`.
2. **`SchemaValidatorGuardrail`** — an `OutputGuardrail` registered as
   a Spring bean (`@Order(40)`). For each LLM response on a
   schema-bearing skill it:
   - Extracts the JSON content (raw or from a ```json``` markdown
     fence) — gives a soft PASS if there's no JSON at all.
   - Runs `JsonSchema.validate(...)` from
     [`com.networknt:json-schema-validator`](https://github.com/networknt/json-schema-validator) (Draft-07).
   - On success → PASS. On failure → BLOCK with the validation messages
     joined into the `reason`.
3. **Corrective-prompt auto-retry** — when the schema-validator
   produces a BLOCK, `DefaultOrchestratorEngine.runOutputGuardrailsWithSchemaRetry`
   appends a corrective user message to the LLM history (`"Your previous
   response failed JSON-schema validation: ... Re-emit a valid response
   that conforms to the schema; do not include any prose outside the
   JSON."`) and re-invokes the model up to
   `agent.output.validation-retries` times (default 2). The **final**
   guardrail outcome is what the caller sees.

Disable globally via `agent.guardrail.output.schema-validator.enabled: false`.
Disable the auto-retry alone via `agent.output.validation-retries: 0`.

### Why a YAML literal block scalar

The framework reads the whole `output-schema` value as the schema text
and passes it straight to the validator. Using YAML's `|` literal
block keeps the JSON readable and preserves whitespace:

```yaml
metadata:
  output-schema: |
    {
      "$schema": "http://json-schema.org/draft-07/schema#",
      "type": "object",
      "required": ["name", "age", "email"],
      "properties": {
        "name":  { "type": "string", "minLength": 1 },
        "age":   { "type": "integer", "minimum": 0, "maximum": 150 },
        "email": { "type": "string", "format": "email" }
      },
      "additionalProperties": false
    }
```

The framework does NOT load schema files from a path here — pasting
inline keeps the SKILL.md self-contained.

---

## What this example ships

- **`profile-skill`** — extracts a `{name, age, email}` profile;
  inline JSON Schema with `required`, `additionalProperties: false`,
  `format: email`, `minimum/maximum` constraints.
- **`default-skill`** — control case, no `output-schema`. The
  guardrail must pass it through unconditionally.
- **`NoopTool`** — placeholder so the SKILL.md linter resolves
  `allowed-tools: [echo]`.

---

## What the test suite proves

[`OutputSchemaApplicationTest.java`](src/test/java/ai/gargantua/example/outputschema/OutputSchemaApplicationTest.java)
boots the full Spring context in the `embedded` profile and pins:

| Test method                                | Pins                                                                                                                       |
|--------------------------------------------|-----------------------------------------------------------------------------------------------------------------------------|
| `profileSkillCarriesSchema`                | `metadata.output-schema` in SKILL.md sets `SkillMeta.hasSchema = true` and `SkillCard.outputSchema` carries the JSON.       |
| `defaultSkillHasNoSchema`                  | A SKILL.md without `output-schema` has `hasSchema = false`.                                                                |
| `skillWithoutSchemaPasses`                 | The guardrail PASSes any response for skills without a schema (zero overhead).                                              |
| `validJsonPasses`                          | A schema-conforming JSON response → PASS.                                                                                  |
| `nonJsonResponsePasses`                    | A non-JSON response on a schema-bearing skill → soft PASS with reason `"Response does not contain JSON…"`.                  |
| `markdownWrappedJsonIsExtracted`           | JSON wrapped in ```json``` markdown fences is extracted before validation.                                                   |
| `missingSchemaInAttributesIsSoftPass`      | If the orchestrator forgot to inject `output_schema` into `inputAttributes`, the guardrail PASSes rather than throwing.    |
| `missingRequiredFieldBlocks`               | Missing required field → BLOCK with the field name in the `reason`.                                                        |
| `wrongTypeBlocks`                          | Wrong type for a property → BLOCK with the type/property in the `reason`.                                                  |
| `outOfRangeBlocks`                         | Value outside declared `minimum/maximum` → BLOCK.                                                                          |
| `additionalPropertiesBlock`                | `additionalProperties: false` rejects unknown fields → BLOCK.                                                              |

The corrective-prompt auto-retry isn't pinned here — it would need a
live LLM to actually re-prompt. The path is documented above and
exercised in the live `docker compose` recipe below.

---

## Run it

### A. Verify the feature (no Docker, no LLM, no Internet)

```bash
cd agent-example-output-schema
mvn -U test
```

Eleven assertions, ~10 s build, no infrastructure.

### B. Watch the auto-retry live (Docker, fully-local Ollama)

```bash
cd agent-example-output-schema
cp .env.example .env
docker compose up -d

curl -X POST http://localhost:8080/api/agent/chat \
  -H 'Content-Type: application/json' \
  -H 'X-User-Id: alice' -H 'X-Session-Id: s1' \
  -d '{"message": "Extract a profile: Alice is 30 years old, email alice@example.com"}'
```

Watch the logs at `DEBUG` for
`ai.gargantua.autoconfigure.guardrails.SchemaValidatorGuardrail` to see
PASS / BLOCK decisions, and at `INFO` on
`ai.gargantua.autoconfigure.DefaultOrchestratorEngine` to catch the
`Schema validation failed (attempt N/M), asking LLM to correct: …`
log line whenever a corrective re-prompt fires.

### C. Embedded mode on the host

```bash
SPRING_PROFILES_ACTIVE=embedded mvn spring-boot:run
```

---

## What this example deliberately does NOT show

| Concern                                       | Where to look                                                                |
|-----------------------------------------------|------------------------------------------------------------------------------|
| Loading a schema from a file path             | The framework currently expects the schema **inline** in `metadata.output-schema`; passing a path would be silently ignored by the validator (a soft PASS via the catch-all). For shared schemas, paste them inline or define a `@Bean` that customises the orchestrator's input attributes. |
| Pre-flight schema validation at boot          | The skill linter doesn't validate the JSON-Schema syntax of `output-schema` blocks today. Bad schemas show up as soft PASS at runtime instead of as a build failure. Tracked as a possible future framework enhancement. |
| Other guardrails (input + output)             | The next per-feature module (`agent-example-guardrails`) covers PII / prompt-injection / scope guardrails.                                            |
