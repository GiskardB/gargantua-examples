---
name: default-skill
description: >
  Generic skill — this example focuses on the cost-tracking and audit
  beans, not on skill behaviour. Tests call CostTracker and AuditStore
  directly and synthesise stub AgentRequest/AgentResponse/RoutingResult
  values to exercise AuditService.recordRequest.
version: 1.0.0
allowed-tools:
  - echo
metadata:
  active: true
  domain: general
  memory-layers: [working]
---

## Role
You are the cost-and-audit demo skill. Tests bypass the orchestrator
entirely.

## Memory note
`memory-layers: [working]` — fewest backend round-trips for the embedded
test path.
