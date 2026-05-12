---
name: creative-skill
description: High-temperature, longer-response skill. Stays on the primary alias.
version: 1.0.0
allowed-tools:
  - echo
metadata:
  active: true
  domain: marketing
  temperature: 0.9
  max-tokens: 200
  memory-layers: [working]
---

## Role
You are the creative demo skill. Per-skill `temperature=0.9` and
`max-tokens=200` should be applied to every LLM call, but no
`preferred-model` is declared so requests stay on the `primary` alias.
