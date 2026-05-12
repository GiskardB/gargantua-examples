# Skill-Level LLM Overrides — Gargantua feature example

Per-feature demo dei tre override SKILL.md per la chiamata LLM:

| Frontmatter            | Effetto                                                                |
|------------------------|------------------------------------------------------------------------|
| `metadata.preferred-model` | Sceglie un alias di modello diverso dal `primary`/`fallback` di default. Risolto da `LlmProviderFactory.resolveModelAlias` (vince anche sulle routing rules). |
| `metadata.temperature` | Override del sampling temperature applicato a ogni `ChatRequest` per quella skill. Wired in v1.2.17. |
| `metadata.max-tokens`  | Override del max output tokens applicato a ogni `ChatRequest` per quella skill. Wired in v1.2.17. |

> **Richiede framework v1.2.17 o successivi.** Le release precedenti
> parsavano `metadata.temperature` / `metadata.max-tokens` ma non li
> applicavano mai al `ChatRequest.Builder`. Solo `preferred-model` era già
> wired (in `LlmProviderFactory.resolveModelAlias`).

---

## La feature

`SkillCard` carica i tre campi dal frontmatter:

```yaml
---
name: precise-skill
metadata:
  temperature: 0.0
  max-tokens: 50
  preferred-model: precise
---
```

L'orchestrator (sia sincrono che streaming) ora:
1. Risolve l'alias del modello via `preferred-model` se presente, altrimenti via `LlmRouter` (routing rules → `primary-alias`).
2. Costruisce `ChatRequest.builder().messages(...).temperature(skillCard.temperature()).maxOutputTokens(skillCard.maxTokens())`.

I tre campi sono **ortogonali**: una skill può usare nessuno, alcuni, o tutti e tre.

---

## Cosa shippa l'esempio

Tre skill che esercitano combinazioni diverse:

| Skill            | `preferred-model` | `temperature` | `max-tokens` |
|------------------|-------------------|---------------|--------------|
| `default-skill`  | —                 | —             | —            |
| `creative-skill` | —                 | `0.9`         | `200`        |
| `precise-skill`  | `precise`         | `0.0`         | `50`         |

`application.yml` dichiara due alias:
- `primary` — usato da `default-skill` + `creative-skill` (che non hanno `preferred-model`).
- `precise` — alias custom dichiarato sotto `agent.llm.models.precise.*`, usato solo da `precise-skill`.

`agent.llm.primary-alias: primary` mappa il default routing alias al bean `primary` (così senza routing rules il framework risolve l'alias `primary`, non il default `default`).

---

## Cosa prova la suite di test

[`SkillLlmOverridesApplicationTest`](src/test/java/ai/gargantua/example/skillllmoverrides/SkillLlmOverridesApplicationTest.java)
boota Spring nel profilo `embedded` e registra due `RecordingChatModel`
beans (uno per ogni alias) che catturano ogni `ChatRequest`. Nessun
LLM reale viene chiamato.

| Test                                          | Pinna                                                                                              |
|-----------------------------------------------|-----------------------------------------------------------------------------------------------------|
| `defaultSkillHasNoOverrides`                  | `default-skill` SKILL.md → SkillCard con `temperature=null`, `maxTokens=null`, `preferredModel=null`. |
| `preciseSkillCarriesAllOverrides`             | `precise-skill` → SkillCard `temperature=0.0`, `maxTokens=50`, `preferredModel=precise`.            |
| `creativeSkillCarriesPartialOverrides`        | `creative-skill` → `temperature=0.9`, `maxTokens=200`, `preferredModel=null`.                       |
| `preferredModelRoutesToCustomAlias`           | Forzando `precise-skill`, l'invocazione raggiunge il bean `precise` e NON `primary`.                |
| `noPreferredModelStaysOnPrimary`              | Forzando `creative-skill`, l'invocazione raggiunge il bean `primary` e NON `precise`.               |
| `defaultSkillUsesPrimary`                     | `default-skill` → bean `primary`.                                                                   |
| `preciseSkillTemperatureIsApplied`            | `ChatRequest.parameters().temperature() == 0.0` per `precise-skill`.                                 |
| `creativeSkillTemperatureIsApplied`           | `ChatRequest.parameters().temperature() == 0.9` per `creative-skill`.                                |
| `defaultSkillDoesNotOverrideTemperature`      | `temperature()` resta `null` quando la skill non lo dichiara (no-override path).                    |
| `preciseSkillMaxTokensIsApplied`              | `ChatRequest.parameters().maxOutputTokens() == 50` per `precise-skill`.                              |
| `creativeSkillMaxTokensIsApplied`             | `ChatRequest.parameters().maxOutputTokens() == 200` per `creative-skill`.                            |
| `defaultSkillDoesNotOverrideMaxTokens`        | `maxOutputTokens()` resta `null` quando la skill non lo dichiara.                                   |

---

## Run it

```bash
cd agent-example-skill-llm-overrides
mvn -U test
```

12 asserzioni, ~10 s, zero infrastruttura.

---

## Cosa l'esempio NON mostra di proposito

| Concern                                       | Dove guardare                                                       |
|-----------------------------------------------|----------------------------------------------------------------------|
| `metadata.preferred-model` che batte una routing rule | `agent-example-llm-routing` testa la grammar delle routing rules; questo esempio testa la precedenza skill-level. |
| Override LLM-side per provider Anthropic / Azure | Il framework usa lo stesso `ChatRequest.Builder` per tutti i provider — qui basta OpenAI-compatible. |
| Cost tracking con preferred-model             | `agent-example-cost-and-audit` copre il pricing lookup per più modelli. |
