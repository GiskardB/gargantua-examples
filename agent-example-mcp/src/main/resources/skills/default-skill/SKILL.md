---
name: default-skill
description: >
  Generic skill — this example focuses on the MCP server beans
  (ChatMcpTool + CapabilitiesMcpResource), not on the skill itself.
  The skill exists so that CapabilitiesMcpResource has a non-empty
  `skills` listing to surface.
version: 1.0.0
allowed-tools:
  - getWeather
metadata:
  active: true
  domain: general
  memory-layers: [working]
---

## Role
You are the MCP demo skill. Tests bypass the orchestrator entirely by
calling MCP beans directly with a mocked OrchestratorEngine.

## Memory note
`memory-layers: [working]` — fewest backend round-trips for the embedded
test path.
