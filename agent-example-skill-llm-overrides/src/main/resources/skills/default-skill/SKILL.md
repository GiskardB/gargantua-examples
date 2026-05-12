---
name: default-skill
description: Generic fallback skill. No LLM overrides — uses the model defaults.
version: 1.0.0
allowed-tools:
  - echo
metadata:
  active: true
  domain: general
  memory-layers: [working]
---

## Role
You are the default skill. No `metadata.temperature` / `metadata.max-tokens`
/ `metadata.preferred-model` are set — every LLM call inherits the
provider defaults from `agent.llm.primary.*`.
