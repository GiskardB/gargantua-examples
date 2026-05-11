# SKILL.md filesystem — Gargantua feature example

Per-feature demo of the **SKILL.md filesystem** authoring path: every
skill is declared by a `SKILL.md` file with YAML frontmatter and a
markdown body that becomes the system prompt. This example ships four
SKILL.md files — each chosen to exercise a different branch of the
parser — plus a minimal placeholder tool so `allowed-tools` resolves.

> Sibling: [`agent-example-skill-annotation`](../agent-example-skill-annotation/)
> shows the Java-annotation authoring path. Both mechanisms can coexist
> in the same app; SKILL.md wins on name collisions.

---

## The feature

`FilesystemSkillRegistry` scans the directory configured by
`agent.skill.path` (default `classpath:skills/`) for any subdirectory
that contains a `SKILL.md` file. Each file consists of:

```
---
<YAML frontmatter>
---
<markdown body>
```

The frontmatter is parsed by `SkillMdParser` ([source](https://github.com/GiskardB/gargantua/blob/main/agent-engine/src/main/java/ai/gargantua/autoconfigure/SkillMdParser.java)).
The body becomes the skill's `systemPrompt`.

### Recognised frontmatter

| Field                             | Type       | Where it lands in `SkillCard`              |
|-----------------------------------|------------|---------------------------------------------|
| `name`                            | string     | `meta.name`                                |
| `description`                     | string     | `meta.description`                         |
| `version`                         | string     | `meta.version` (default `"1.0.0"`)         |
| `allowed-tools`                   | list       | `allowedTools` (also accepts whitespace string for backward compat) |
| `references`                      | list       | `references` (also auto-extended by the `references/` folder, see below) |
| `examples`                        | list       | `examples` (surfaced in the A2A Agent Card) |
| `metadata.active`                 | boolean    | `meta.active` (default `true`)             |
| `metadata.domain`                 | string     | `meta.domain` (default `"general"`)        |
| `metadata.output-schema`          | string     | `outputSchema` resource path + `meta.hasSchema` |
| `metadata.allowed-roles`          | list       | `meta.allowedRoles` (Set\<String\>)         |
| `metadata.temperature`            | number     | `temperature` override                     |
| `metadata.max-tokens`             | int        | `maxTokens` override                       |
| `metadata.preferred-model`        | string     | `preferredModel` alias                     |
| `metadata.memory-layers`          | list       | `enabledMemoryLayers` (Set\<MemoryLayer\>; null = all) |
| `metadata.knowledge-base`         | string     | `ragConfig.knowledgeBase`                  |
| `metadata.rag-max-results`        | int        | `ragConfig.maxResults` (default 5)         |
| `metadata.rag-min-score`          | number     | `ragConfig.minScore` (default 0.3)         |

### `references/` folder auto-append

If a skill directory contains a `references/` subfolder, every file
inside it is appended to `SkillCard.references()` on `load()` (in
lexicographic order). Frontmatter-declared `references` stay first.
Useful for shipping policy documents alongside the skill — the prompt
composer concatenates them into the LLM context.

### Skill linter

The example's `pom.xml` wires the framework's
`agent-skill-linter-maven-plugin` into the `verify` phase, so
`mvn verify` (or `mvn package`) catches broken frontmatter at build
time — e.g. an unresolvable name in `allowed-tools` or a malformed YAML
block.

---

## What this example ships

```
src/main/resources/skills/
├── market-skill/
│   └── SKILL.md          ← "everything-on": every supported frontmatter field
├── support-skill/
│   ├── SKILL.md          ← demonstrates the references/ folder
│   └── references/
│       ├── refund-policy.md
│       └── sla.md
├── inactive-skill/
│   └── SKILL.md          ← metadata.active: false
└── default-skill/
    └── SKILL.md          ← minimal greeter
```

And a tiny [`MarketTool`](src/main/java/ai/gargantua/example/skillfilesystem/tools/MarketTool.java)
with two trivial `@AgentTool` methods so the linter accepts the
`allowed-tools` entries.

---

## What the test suite proves

[`SkillFilesystemApplicationTest.java`](src/test/java/ai/gargantua/example/skillfilesystem/SkillFilesystemApplicationTest.java)
boots the full Spring context in the `embedded` profile and asserts
every parser dimension:

| Test method                                | Pins                                                                                                                  |
|--------------------------------------------|------------------------------------------------------------------------------------------------------------------------|
| `allFourSkillsDiscovered`                  | All four SKILL.md files are picked up by the recursive directory scan.                                                |
| `marketSkillCoreMeta`                      | `name`, `description`, `version` round-trip; `source = FILESYSTEM`; `active = true`.                                  |
| `marketSkillRoleAndDomain`                 | `metadata.domain` + `metadata.allowed-roles` round-trip into `SkillMeta`.                                             |
| `marketSkillAllowedTools`                  | `allowed-tools` YAML list parses into `SkillCard.allowedTools`.                                                       |
| `marketSkillSystemPromptIsBody`            | Body becomes `systemPrompt`; frontmatter delimiters do not leak.                                                      |
| `marketSkillLlmOverrides`                  | `temperature`, `max-tokens`, `preferred-model` propagate.                                                             |
| `marketSkillMemoryLayers`                  | `memory-layers: [working, knowledge]` produces the matching `MemoryLayer` set.                                        |
| `marketSkillRagConfig`                     | `knowledge-base` + `rag-max-results` + `rag-min-score` build a `RagConfig`.                                           |
| `marketSkillExamples`                      | Top-level `examples` list propagates.                                                                                 |
| `marketSkillFrontmatterReferences`         | Top-level `references` list propagates (and is NOT extended when no `references/` folder is present).                  |
| `supportSkillFolderReferences`             | `references/*.md` files are auto-appended on `load()` (lexicographic order, deterministic).                          |
| `inactiveSkillIsListedButInactive`         | `metadata.active: false` round-trips into `SkillMeta.active() == false`.                                              |

---

## Run it

### A. Verify the feature (no Docker, no LLM, no Internet)

```bash
cd agent-example-skill-filesystem
mvn -U test
```

Twelve assertions, ~10 s build, no infrastructure.

### B. Run the agent (Docker, fully-local Ollama)

```bash
cd agent-example-skill-filesystem
cp .env.example .env
docker compose up -d
```

### C. Embedded mode on the host

```bash
SPRING_PROFILES_ACTIVE=embedded mvn spring-boot:run
```

---

## What this example deliberately does NOT show

| Concern                                | Where to look                                                |
|----------------------------------------|--------------------------------------------------------------|
| Java-annotation skill authoring        | [`agent-example-skill-annotation`](../agent-example-skill-annotation/) |
| Tool annotations themselves            | sibling `agent-example-tool-*` modules                       |
| Hot-reload of edited SKILL.md files    | Set `agent.skill.hot-reload: true` in `application.yml` — the framework wires a `HotReloadSkillRegistry` watching the skill path. Not covered here because the file-watch side-effects are hard to assert deterministically. |
