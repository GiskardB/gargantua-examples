---
name: default-skill
description: >
  Generic skill — this example focuses on the LLM routing-rule
  grammar, not on skill behaviour. Tests construct LlmRoutingContext
  directly against the autowired LlmRouter bean, so the skill body
  is mostly cosmetic.
version: 1.0.0
allowed-tools:
  - echo
metadata:
  active: true
  domain: general
  memory-layers: [working]
---

## Role
You are the LLM-routing demo skill. Tests bypass routing entirely by
calling `LlmRouter#evaluateAll` and `LlmRouter#resolve` directly.

## Memory note
`memory-layers: [working]` — fewest backend round-trips for the embedded
test path.
