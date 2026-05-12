# gargantua-examples

Reference agents built on the [Gargantua framework](https://github.com/GiskardB/gargantua).

### Per-feature examples

Single-feature demos — one annotation or capability at a time, each one verifiable with `mvn test`:

| Example | Feature it pins |
|---------|-----------------|
| [`agent-example-tool-basics`](agent-example-tool-basics/) | `@AgentTool` itself — name override, description, `parallelizable`, registration scan, JSON arg/return contract. Zero infra. |
| [`agent-example-tool-retry`](agent-example-tool-retry/) | `@ToolRetry` — exponential backoff via Resilience4j, `retryOn`/`abortOn` filters, exhaustion → JSON error, Micrometer metrics. Zero infra. |
| [`agent-example-tool-cache`](agent-example-tool-cache/) | `@CacheableToolResult` — read-through cache, `keyParams` selectivity, TTL expiry, GLOBAL/USER/SESSION scopes, hit/miss counters. In-memory backend in embedded mode (v1.2.5+). |
| [`agent-example-tool-approval`](agent-example-tool-approval/) | `@RequiresApproval` — HITL metadata, `ApprovalStore` lifecycle (save/get/resolve/expiry), REST resolution at `/api/agent/approval/{id}`. Zero infra. |
| [`agent-example-tool-rbac`](agent-example-tool-rbac/) | `@RequiresRole` — registry-level RBAC gate, any-of semantics, `super-admin` wildcard, fail-closed without `SecurityContext`. Zero infra. |
| [`agent-example-skill-annotation`](agent-example-skill-annotation/) | `@AgentSkill` — skill defined inline in Java, auto-detected `@AgentTool` methods, `PROMPT` field, `examples`/`temperature` overrides. v1.2.7+ wires the annotated skills into the `SkillRegistry`. |
| [`agent-example-skill-filesystem`](agent-example-skill-filesystem/) | SKILL.md filesystem authoring — YAML frontmatter (name/version/allowed-tools/metadata), markdown body → system prompt, `references/` folder auto-append, `metadata.active: false`. Zero infra. |
| [`agent-example-agents-flow`](agent-example-agents-flow/) | `@AgentsFlow` — multi-step skill pipelines, `SEQUENTIAL` / `LOOP` / `PARALLEL` step types, per-step instructions, `FlowRegistry` discovery. Zero infra. |
| [`agent-example-routing-strategy`](agent-example-routing-strategy/) | Skill routing — `semantic` (ONNX embeddings), `llm` (routing model), `hybrid` (default); below-threshold + LLM-failure fallback paths. Zero infra. |
| [`agent-example-rag`](agent-example-rag/) | RAG — SKILL.md `knowledge-base` → `RagConfig`, `VectorStorePort.search` (in-memory backend), `RagEnricher` injects `RELEVANT_DOCUMENTS` into the prompt, respects `rag-max-results`/`rag-min-score`. Zero infra. |
| [`agent-example-memory-layers`](agent-example-memory-layers/) | 3-layer memory — `WorkingMemoryPort` / `EpisodicMemoryPort` / `KnowledgeMemoryPort` CRUD, `MemoryComposer` parallel fetch + token-budget truncation (knowledge → episodic), SKILL.md `memory-layers` opt-out. Zero infra. |
| [`agent-example-output-schema`](agent-example-output-schema/) | JSON-Schema output guardrail — SKILL.md inline `metadata.output-schema` → `SchemaValidatorGuardrail`, PASS/BLOCK with validation messages, markdown-fence extraction, corrective-prompt auto-retry documented. Zero infra. |
| [`agent-example-guardrails`](agent-example-guardrails/) | Input + output guardrail pipeline — `MaxLength`, `PromptInjection`, `TopicScope`, `PiiInput` (masking + `pii_map`), `PiiOutput` (restore from input map / regex fallback). 12 assertions, zero infra. |
| [`agent-example-llm-routing`](agent-example-llm-routing/) | LLM routing rules / model-pool — priority ordering, enabled flag, every condition operator (EQ/IN/NOT_IN/GT/LT/GTE/LTE/REGEX/CONTAINS), AND/OR, time-window (cross-midnight), day-of-week, random-sampling, attribute-match, legacy attribute-equality fallback, no-match → primary-alias. 17 assertions, zero infra. |
| [`agent-example-cost-and-audit`](agent-example-cost-and-audit/) | Cost tracking + audit trail — `CostTracker` pricing lookup (nested / colon / model-only forms), USD arithmetic, disabled short-circuit. `AuditStore` CRUD via in-memory adapter, `AuditService.recordRequest` event-mapping (incl. `GuardrailEvent`), tenant/session/user/time-range queries, audit-disabled short-circuit. 16 assertions, zero infra. |
| [`agent-example-mcp`](agent-example-mcp/) | MCP server — `agent.mcp.enabled=true` activates the auto-config; pins `ChatMcpTool` gateway (request forwarding + JSON error sentinels) and `CapabilitiesMcpResource` (server identity + gateway tool entry + live skill/tool listings). 9 assertions with a mocked `OrchestratorEngine`, zero infra. |

### Scenario examples

End-to-end agents combining many features:

| Example | What it shows |
|---------|---------------|
| [`agent-example-weather`](agent-example-weather/) | Smallest useful agent: 1 skill, 1 tool, real HTTP integration (Open-Meteo). Fully-local Ollama, no API keys. |
| [`agent-example-cookbook`](agent-example-cookbook/) | 6 skills + 5 tool classes covering all annotation combos, JSON-Schema structured output, fully-local Ollama. |
| [`agent-example-fitcoach`](agent-example-fitcoach/) | 6 skills, RAG, RBAC, HITL, structured output, `@AgentsFlow`, multi-tier memory, Bifrost gateway with Azure OpenAI. |

Each example is a standalone Maven project — no parent pom, no shared reactor.

## How dependencies are resolved

The framework artifacts (`agent-engine`, `agent-mcp-server`, `agent-skill-linter-maven-plugin`) are consumed via [JitPack](https://jitpack.io). Each example's `pom.xml` declares:

```xml
<repositories>
  <repository>
    <id>jitpack.io</id>
    <url>https://jitpack.io</url>
  </repository>
</repositories>

<dependency>
  <groupId>com.github.giskardb.gargantua</groupId>
  <artifactId>agent-engine</artifactId>
  <version>${gargantua.version}</version>
</dependency>
```

The `gargantua.version` property defaults to `develop-SNAPSHOT` (tip of `develop` on the framework repo). Change it to a tagged release (e.g. `v1.0.0`) to pin against a specific framework version.

> First build is slow — JitPack compiles the framework on demand and caches the result. Subsequent builds are fast.

## Running an example

```bash
cd agent-example-weather       # or cookbook / fitcoach
cp .env.example .env           # fill in keys if needed (weather/cookbook need none)
docker compose up -d
```

Then open <http://localhost:8080/chat>. See each example's own README for details.

## Local framework development

If you're hacking on the framework itself, clone both repos as siblings:

```
~/code/
├── gargantua/             # the framework
└── gargantua-examples/    # this repo
```

Run `mvn install` once in `gargantua/` to populate `~/.m2`. Maven resolves locally-installed artifacts before hitting JitPack, so changes to the framework flow through immediately into example builds.
