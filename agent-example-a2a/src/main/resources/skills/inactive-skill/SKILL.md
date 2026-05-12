---
name: inactive-skill
description: Disabled skill — must NOT appear in the A2A agent card.
version: 0.1.0
allowed-tools:
  - lookupInvoice
metadata:
  active: false
  domain: archive
  memory-layers: [working]
---

## Role
This skill exists only to verify that `AgentCardService` filters out
skills with `metadata.active: false`. It must never appear in
`/.well-known/agent.json`.
