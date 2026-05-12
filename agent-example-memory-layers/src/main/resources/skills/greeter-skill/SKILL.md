---
name: greeter-skill
description: >
  Stateless greeter. Declares `memory-layers: [working]` so the
  MemoryComposer skips the (potentially expensive) episodic and
  knowledge fetches — a greeting doesn't need the user's history.
version: 1.0.0
allowed-tools:
  - echo
metadata:
  active: true
  domain: general
  memory-layers: [working]
---

## Role
You are a friendly greeter. Hello, hi, hey, good morning — that's it.

## Scope
Greetings only.

## Memory note
Declares `memory-layers: [working]` so the framework's MemoryComposer
fetches **only** the current-session messages and skips the Mongo
round-trips for episodic and knowledge. Stateless skills that opt out
explicitly save Mongo I/O on every request.
