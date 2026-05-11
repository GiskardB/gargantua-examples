# gargantua-examples

Reference agents built on the [Gargantua framework](https://github.com/GiskardB/gargantua).

### Per-feature examples

Single-feature demos — one annotation or capability at a time, each one verifiable with `mvn test`:

| Example | Feature it pins |
|---------|-----------------|
| [`agent-example-tool-basics`](agent-example-tool-basics/) | `@AgentTool` itself — name override, description, `parallelizable`, registration scan, JSON arg/return contract. Zero infra. |

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
