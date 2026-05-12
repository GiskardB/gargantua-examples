---
name: default-skill
description: >
  Generic skill — the example focuses on the guardrail pipeline, not
  on skill behaviour. Tests construct GuardrailInputContext /
  GuardrailOutputContext directly against the autowired guardrail
  beans, so the skill body is mostly cosmetic.
version: 1.0.0
allowed-tools:
  - echo
metadata:
  active: true
  domain: general
  memory-layers: [working]
---

## Role
You are the guardrails demo skill. Tests bypass routing entirely by
calling each guardrail bean directly.

## Memory note
`memory-layers: [working]` — fewest backend round-trips for the embedded
test path.
