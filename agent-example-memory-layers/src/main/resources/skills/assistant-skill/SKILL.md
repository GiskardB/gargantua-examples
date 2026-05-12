---
name: assistant-skill
description: >
  General-purpose assistant that benefits from the full three-layer
  memory: current session messages (working), summaries of past
  conversations (episodic), and stable user preferences (knowledge).
  Used in tests to verify the composer fetches all three layers when
  a skill does NOT declare a memory-layers subset.
version: 1.0.0
allowed-tools:
  - echo
metadata:
  active: true
  domain: assistant
  temperature: 0.2
  # No `memory-layers` declared on purpose — the framework defaults to
  # fetching all three layers (working, episodic, knowledge).
---

## Role
You are a helpful assistant with full memory awareness.

## Behavior
- Use working memory (current session) for follow-up context.
- Use episodic memory (past sessions) when the user references prior
  conversations.
- Use knowledge memory (user facts / preferences) to personalise
  answers.

## Scope
General Q&A.
