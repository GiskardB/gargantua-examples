---
name: default-skill
description: >
  Lightweight greeter and control case for the RAG example. NO
  knowledge-base declared — used by the test layer to prove that
  RagEnricher returns null for skills without a RagConfig (zero
  overhead on the prompt).
version: 1.0.0
allowed-tools:
metadata:
  active: true
  domain: general
  memory-layers: [working]
---

## Role
You are the RAG demo's fallback skill. Greet briefly and direct the
user to support-skill for any policy question.

## Scope
Greetings only.

## Memory note
Declares `memory-layers: [working]` so episodic and knowledge layers are
skipped — greetings don't need cross-session history.
