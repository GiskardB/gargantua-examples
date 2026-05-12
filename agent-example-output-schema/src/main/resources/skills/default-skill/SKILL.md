---
name: default-skill
description: >
  Lightweight greeter — no output schema declared. Used in tests as
  the control case to prove the SchemaValidatorGuardrail passes
  unconditionally for skills without a schema (`hasSchema=false`).
version: 1.0.0
allowed-tools:
metadata:
  active: true
  domain: general
  memory-layers: [working]
---

## Role
You are the schema-validation demo's fallback skill. Greet briefly and
direct the user to profile-skill for any "extract profile" request.

## Scope
Greetings only.

## Memory note
Declares `memory-layers: [working]` so episodic and knowledge layers are
skipped — greetings don't need cross-session history.
