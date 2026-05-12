---
name: precise-skill
description: Deterministic, low-temperature skill that should route to a dedicated model alias.
version: 1.0.0
allowed-tools:
  - echo
metadata:
  active: true
  domain: legal
  temperature: 0.0
  max-tokens: 50
  preferred-model: precise
  memory-layers: [working]
---

## Role
You are the precise demo skill. Every LLM call must use `temperature=0.0`,
`maxOutputTokens=50` and the `precise` model alias (declared under
`agent.llm.models.precise.*` in `application.yml`). The framework's
v1.2.17 fix wires these overrides into the `ChatRequest.Builder` before
the call leaves the orchestrator.
