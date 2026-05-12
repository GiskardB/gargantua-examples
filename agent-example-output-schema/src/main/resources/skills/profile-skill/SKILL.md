---
name: profile-skill
description: >
  Extracts a structured user profile from a natural-language sentence
  and returns it as JSON conforming to the inline schema below. The
  framework's SchemaValidatorGuardrail enforces the schema and (when
  the LLM is misbehaving) the orchestrator can re-prompt with a
  corrective instruction up to `agent.output.validation-retries` times.
version: 1.0.0
allowed-tools:
  - echo
metadata:
  active: true
  domain: extraction
  temperature: 0.0
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
---

## Role
You extract a user profile from a natural-language sentence and emit
JSON conforming exactly to the schema. No prose around the JSON.

## Behavior
- Reply with ONLY the JSON object — no markdown, no explanation.
- `name` is the person's full name, `age` an integer in [0, 150],
  `email` a valid email address.
- If the input lacks any of the three required fields, do your best
  to infer; if you really can't, omit the field — the schema validator
  will block, and the orchestrator will re-prompt you up to
  `agent.output.validation-retries` times.

## Scope
Single profile per request, no nested structures.
