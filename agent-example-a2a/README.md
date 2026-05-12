# A2A Protocol — Gargantua feature example

Per-feature demo dell'**Agent-to-Agent Protocol** (v1.0). Espone:

- `GET /.well-known/agent.json` — discovery endpoint A2A standard.
- `POST /a2a` — JSON-RPC 2.0 con i metodi `message/send`, `tasks/get`,
  `tasks/cancel`.
- `HttpA2AClient` — client lato chiamante per parlare con altri agenti
  A2A-compatibili (incluso lo stesso agente in loop-back).

L'esempio pinna l'intero round-trip end-to-end senza un vero LLM —
l'`OrchestratorEngine` è stubbato via `@TestConfiguration`. Bootstrap
su porta random e l'`HttpA2AClient` reale parla loop-back con la
stessa app.

---

## La feature

### Server side (sempre attiva quando `agent-engine` è sul classpath)

| Componente                      | Cosa fa                                                                                            |
|----------------------------------|----------------------------------------------------------------------------------------------------|
| `AgentCardService`               | Costruisce un `AgentCard` (record A2A v1.0) da `agent.api.*` + `SkillRegistry.listMeta()`.         |
| `CapabilitiesController`         | REST: serve l'`AgentCard` su `/.well-known/agent.json` + JSON-RPC su `/a2a`.                       |
| `HttpA2AClient` (`A2AClient`)    | Client `RestClient` per chiamare un agente A2A remoto (discovery + `message/send` + `tasks/*`).    |

L'`AgentCard` riporta: `name`, `description`, `version`, `url`,
`protocolVersion`, `capabilities`, `defaultInputModes`,
`defaultOutputModes`, lista `skills`, `provider` (nullable),
`authSchemes`. Per ogni skill:

- `id` / `name` / `description` / `version` (dal SKILL.md)
- `domain` (estensione Gargantua, fuori dallo spec A2A)
- `tags` = `[domain, …allowedTools]`
- `examples` (frontmatter `examples:` di SKILL.md, non `metadata.examples`)

Le skill con `metadata.active: false` sono **escluse** dal card.

### `/a2a` JSON-RPC

| Metodo            | Mappa a                                                                                       |
|-------------------|----------------------------------------------------------------------------------------------|
| `message/send`    | `OrchestratorEngine.invoke(...)` → restituisce un `A2ATask` con `status=completed`, `artifacts=[response]` e `metadata.skillUsed`. `params.skillHint` → `AgentRequest.forceSkill` (bypass del routing). Eccezioni dell'engine diventano `status=failed` con `metadata.error`. Codici di errore JSON-RPC: `-32600` (no method), `-32601` (unknown method), `-32602` (params non validi). |
| `tasks/get`       | Restituisce `-32602` — i task sono eseguiti sincronamente, niente storage al momento.         |
| `tasks/cancel`    | Idem.                                                                                         |

---

## Cosa shippa l'esempio

- `application.yml` con `agent.api.display-name`, `agent.api.version`,
  `agent.api.description` valorizzati così che ogni campo del card
  abbia un valore non-default da pinnare.
- `billing-skill` (active, con `domain`, `allowed-tools`, `examples`)
  e `inactive-skill` (`metadata.active: false`) — coprono il filtraggio
  e la propagazione di tag/examples.
- `BillingTool` (`@AgentTool`) referenziato da `billing-skill`
  così che `tags` includa effettivamente `lookupInvoice`.
- Un `@TestConfiguration` che stubba `OrchestratorEngine` con Mockito.

---

## Cosa prova la suite di test

[`A2AApplicationTest.java`](src/test/java/ai/gargantua/example/a2a/A2AApplicationTest.java)
boota il contesto Spring sul profilo `embedded`, porta random, 14
asserzioni:

| Test method                                        | Pinna                                                                                              |
|----------------------------------------------------|----------------------------------------------------------------------------------------------------|
| `agentCardCarriesServerIdentity`                   | Il card riporta `name` / `description` / `version` da `agent.api.*`, `protocolVersion=1.0`, default I/O modes, `authSchemes=[none]`. |
| `agentCardOmitsInactiveSkills`                     | `billing-skill` appare; `inactive-skill` (metadata.active=false) è filtrato.                       |
| `agentCardSkillTagsAndExamples`                    | `tags = [domain, …allowedTools]`, `examples` propagati da SKILL.md.                                |
| `wellKnownAgentJsonExposesCard`                    | `GET /.well-known/agent.json` → 200 + `AgentCard` JSON con `url` derivato dalla request.           |
| `a2aMessageSendCompletesTask`                      | `POST /a2a method=message/send` con `message.parts` → task `completed` con artifact + metadata.    |
| `a2aSkillHintBecomesForceSkill`                    | `params.skillHint=billing-skill` → l'`AgentRequest` forwardato all'engine ha `forceSkill="billing-skill"` e `userId="a2a-agent"`. |
| `a2aEngineExceptionYieldsFailedTask`               | Eccezione dell'engine → `status=failed`, `metadata.error=<message>`, **mai** un JSON-RPC error.     |
| `a2aMissingMethodReturnsInvalidRequest`            | Body senza `method` → JSON-RPC `-32600` Invalid Request.                                            |
| `a2aUnknownMethodReturnsMethodNotFound`            | `method=nope/notReal` → `-32601` Method not found, con il nome del metodo nel messaggio.            |
| `a2aMissingMessagePartsReturnsInvalidParams`       | `message/send` senza `message.parts` → `-32602`.                                                    |
| `a2aTaskGetAndCancelAreNotImplementedSynchronously`| `tasks/get` e `tasks/cancel` → entrambi `-32602` (storage non implementato).                        |
| `httpClientDiscoverRoundTrip`                      | `HttpA2AClient.discover(baseUrl)` chiama `/.well-known/agent.json` e parsa l'`AgentCard`.           |
| `httpClientSendTaskRoundTrip`                      | `HttpA2AClient.sendTask(...)` round-trip completo: invia, riceve task `completed`, parsing artifact + metadata. |
| `httpClientGetTaskHandlesUnknownId`                | `HttpA2AClient.getTask(...)` su un id qualsiasi → `Optional.empty` (collassa il JSON-RPC error server-side). |

---

## Run it

### A. Verifica la feature (no Docker, no LLM, no Internet)

```bash
cd agent-example-a2a
mvn -U test
```

14 asserzioni, ~12 s, zero infrastruttura.

### B. Live (Docker, Ollama fully-local)

```bash
cd agent-example-a2a
cp .env.example .env
docker compose up -d
```

Una volta su, qualsiasi peer A2A può scoprire l'agente puntando a
`http://localhost:8080/.well-known/agent.json` e mandare task con
JSON-RPC su `http://localhost:8080/a2a`.

### C. Embedded mode sul host

```bash
SPRING_PROFILES_ACTIVE=embedded mvn spring-boot:run
```

---

## Cosa l'esempio NON mostra di proposito

| Concern                                       | Dove guardare                                                                       |
|-----------------------------------------------|--------------------------------------------------------------------------------------|
| Persistenza dei task A2A (`tasks/get` reale)  | Out of scope — al momento i task sono sincroni, lo storage è sulla roadmap.          |
| Push notifications / streaming                | `AgentCard.capabilities.streaming/pushNotifications` esistono ma sono `false`.       |
| Auth A2A (apiKey / bearer / oauth2)           | `AgentAuthScheme` supporta gli schemi; la pipeline auth è separata e fuori scope.    |
| Multi-agente reale (peer-to-peer)             | `HttpA2AClient` qui parla loop-back; in produzione punta all'URL di un agente terzo. |
| Conversion to A2A `Part` types `file` / `data` | Solo `text` parts coperti — `file`/`data` sono nello spec ma non nel pipeline qui.  |
