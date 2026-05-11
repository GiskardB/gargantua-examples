---
name: inactive-skill
description: >
  Demonstrates the `metadata.active: false` flag. The skill IS discovered
  by the registry (it appears in listMeta()) but is flagged inactive and
  the router will not select it.
version: 1.0.0

allowed-tools:
  - lookup

metadata:
  active: false
  domain: shelf
---

## Role
You are an inactive demo skill. The framework still parses you and exposes
your metadata, but routing skips you because `metadata.active` is false.

## Behavior
- Never selected — nothing to do.

## Scope
None.
