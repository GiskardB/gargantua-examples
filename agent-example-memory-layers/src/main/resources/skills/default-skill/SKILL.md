---
name: default-skill
description: >
  Routing fallback. Declares no memory-layers, so the framework
  fetches all three layers by default. Used by tests as the
  always-active control case.
version: 1.0.0
allowed-tools:
metadata:
  active: true
  domain: general
---

## Role
You are the 3-layer memory demo's fallback skill.

## Scope
Anything that doesn't match assistant-skill or greeter-skill.
