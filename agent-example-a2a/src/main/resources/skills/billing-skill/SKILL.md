---
name: billing-skill
description: Looks up invoices and answers basic billing questions.
version: 1.0.0
allowed-tools:
  - lookupInvoice
examples:
  - "What's the status of invoice 12345?"
  - "Show me the amount on my latest invoice."
metadata:
  active: true
  domain: billing
  memory-layers: [working]
---

## Role
You are the billing demo skill. Tests bypass the orchestrator — this
skill exists so the A2A agent card has a real entry to surface
(name + description + version + domain + tags + examples).
