# MCP Server — Gargantua feature example

Per-feature demo of the **Model Context Protocol** integration: a
single property flip (`agent.mcp.enabled: true`) exposes the agent
to external MCP clients (Claude Desktop, Cursor, VS Code, …) as a
chat gateway plus a capability-discovery resource.

The integration is pinned end-to-end by direct calls to the autowired
beans — no LLM is invoked, the `OrchestratorEngine` is mocked in
tests.

> **Requires framework v1.2.13 or later.** Earlier releases declared
> the MCP beans with `@Component` and relied on classpath scanning
> that did not reach user-app base packages — `agent.mcp.enabled=true`
> activated the auto-config but neither bean ever made it into the
> user's context. v1.2.13 moved the bean registrations into the
> auto-config's `@Bean` factories.

---

## The feature

When `agent.mcp.enabled=true`:

- **`AgentMcpServerAutoConfiguration`** is activated and binds
  `AgentMcpProperties` (everything under `agent.mcp.*`: server identity,
  transport type + path, gateway tool name + description, mode,
  security toggles).
- **`ChatMcpTool`** is registered as a single gateway tool that wraps
  `OrchestratorEngine.invoke()`. Every MCP `tools/call` for the
  configured tool name (default `agent-chat`, overridden here to
  `ask-the-agent`) becomes a full agent invocation through the
  pipeline (guardrails → routing → memory → LLM → output guardrails).
  Each request carries `source=mcp` and `mcp.tool=<gateway-name>` as
  `contextAttributes` so downstream guardrails and enrichers can
  detect MCP traffic. Blank / null messages and engine exceptions are
  serialised to **JSON error sentinels** so the MCP client never sees
  a Java stack trace.
- **`CapabilitiesMcpResource`** is registered to answer the
  `agent://capabilities` MCP resource read. It reports:
  - server identity (`name`, `version`, `description`, `mode`)
  - the gateway tool entry (`tool-name` → `tool-description`)
  - the live skill list (from `SkillRegistry.listMeta()`)
  - the live agent-tool list (from `ToolRegistry.getToolDefinitions()`,
    including `requiresApproval`, `dangerous`, `parallelizable` flags)

  Both registries are injected via `ObjectProvider`, so capabilities
  still resolves correctly in minimal gateway-only deployments with no
  skills or no tools.

| Property                                | Default            | Used here                                                       |
|-----------------------------------------|--------------------|------------------------------------------------------------------|
| `agent.mcp.enabled`                     | `false`            | `true`                                                           |
| `agent.mcp.mode`                        | `standalone`       | `standalone`                                                     |
| `agent.mcp.server.name` / `.version` / `.description` | defaults | `gargantua-demo-mcp` / `1.0.0` / "Demo Gargantua agent exposed over MCP." |
| `agent.mcp.transport.type` / `.path`    | `sse` / `/mcp`     | `sse` / `/mcp`                                                   |
| `agent.mcp.gateway.tool-name` / `.tool-description` | `agent-chat` / canned | `ask-the-agent` / "Send a natural-language message to the Gargantua agent." |
| `agent.mcp.security.auth-required` / `.token-header` | `false` / `Authorization` | `false` / `Authorization` |

---

## What this example ships

- `application.yml` with the full `agent.mcp.*` subtree populated so
  every property has a non-default value the tests can pin.
- A `default-skill` SKILL.md and a `WeatherTool` `@AgentTool` so
  `CapabilitiesMcpResource` has non-empty `skills` and `agentTools`
  listings to surface.
- A `@TestConfiguration` that stubs `OrchestratorEngine` with Mockito
  so `mvn test` exercises the gateway forwarding without a real LLM.

---

## What the test suite proves

[`McpApplicationTest.java`](src/test/java/ai/gargantua/example/mcp/McpApplicationTest.java)
boots the Spring context in the `embedded` profile (9 assertions):

| Test method                                       | Pins                                                                                                |
|---------------------------------------------------|-----------------------------------------------------------------------------------------------------|
| `beansAreRegistered`                              | Both MCP beans + `AgentMcpProperties` are in the context when `agent.mcp.enabled=true`.             |
| `propertiesRoundTrip`                             | Every value declared in `application.yml` round-trips through `AgentMcpProperties`.                 |
| `chatForwardsRequestWithMcpAttributes`            | `chat(message, userId, sessionId)` invokes the engine with `source=mcp` + `mcp.tool=<gateway-name>` context attributes. |
| `chatOneArgOverloadGeneratesSession`              | `chat(message)` defaults `userId=mcp-client` and generates a fresh non-blank session id.            |
| `chatRejectsBlankAndNull`                         | Blank / null user messages return the `{"error":"empty user message"}` sentinel without touching the engine. |
| `chatHandlesEngineException`                      | Engine exceptions become `{"error":"agent invocation failed: …"}` with the message JSON-escaped.    |
| `capabilitiesIncludeServerIdentity`               | `getCapabilities()` returns name / version / description / mode + the single gateway tool entry.    |
| `capabilitiesIncludeLiveSkills`                   | The skill listing reflects `default-skill` from SKILL.md with version / domain / active flags.      |
| `capabilitiesIncludeLiveAgentTools`               | The agent-tool listing reflects `getWeather` with description + approval / dangerous / parallelizable flags. |

---

## Run it

### A. Verify the feature (no Docker, no LLM, no Internet)

```bash
cd agent-example-mcp
mvn -U test
```

9 assertions, ~10 s build, no infrastructure.

### B. Live (Docker, fully-local Ollama)

```bash
cd agent-example-mcp
cp .env.example .env
docker compose up -d
```

Once the stack is up, point an MCP-capable client (Claude Desktop,
Cursor, VS Code with the MCP extension, …) at the SSE endpoint
`http://localhost:8080/mcp`. The client will discover one tool —
`ask-the-agent` — and one resource — `agent://capabilities`.

### C. Embedded mode on the host

```bash
SPRING_PROFILES_ACTIVE=embedded mvn spring-boot:run
```

---

## What this example deliberately does NOT show

| Concern                                       | Where to look                                                                       |
|-----------------------------------------------|--------------------------------------------------------------------------------------|
| MCP `stdio` transport                         | Set `agent.mcp.transport.type=stdio`; we use SSE here for the HTTP-based dev loop.   |
| Tenant-aware MCP auth                         | `agent.mcp.security.auth-required=true` + `token-header` are wired but out of scope. |
| Multi-tool gateways                           | Today there is exactly one gateway tool (`ChatMcpTool`); roadmap will surface skills as individual MCP tools. |
| Prompts / sampling MCP capabilities           | Not implemented in the framework yet.                                                |
| Live SDK-level wiring with Claude Desktop     | The framework integrates via `mcp-spring-webmvc`; client setup is in the MCP docs.   |
