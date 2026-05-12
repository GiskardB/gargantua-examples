package ai.gargantua.example.outputschema;

import ai.gargantua.autoconfigure.guardrails.SchemaValidatorGuardrail;
import ai.gargantua.core.guardrail.GuardrailOutputContext;
import ai.gargantua.core.guardrail.GuardrailOutputResult;
import ai.gargantua.core.guardrail.GuardrailVerdict;
import ai.gargantua.core.skill.SkillCard;
import ai.gargantua.core.skill.SkillMeta;
import ai.gargantua.core.skill.SkillRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Pins the JSON-Schema output guardrail contract end-to-end with no LLM:
 *
 * <ol>
 *   <li>SKILL.md {@code metadata.output-schema} round-trips into
 *       {@code SkillMeta.hasSchema = true} and {@code SkillCard.outputSchema}.</li>
 *   <li>{@link SchemaValidatorGuardrail} is a no-op for skills without a
 *       schema (PASS) and for non-JSON responses (PASS with reason).</li>
 *   <li>For schema-bearing skills the guardrail accepts conforming JSON
 *       (PASS) and BLOCKs non-conforming JSON with the validation message
 *       in the reason.</li>
 *   <li>Markdown-wrapped JSON ({@code ```json … ```}) is extracted and
 *       validated like raw JSON.</li>
 *   <li>A schema-bearing skill whose {@code inputAttributes} don't carry
 *       the schema (orchestrator bug or misconfig) results in a soft PASS —
 *       the guardrail never throws.</li>
 * </ol>
 *
 * <p>The corrective-prompt auto-retry on schema failure is implemented in
 * {@code DefaultOrchestratorEngine.runOutputGuardrailsWithSchemaRetry} —
 * exercising it needs a live LLM and is out of scope for this test layer.
 * See the README for the live-demo recipe.</p>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@ActiveProfiles("embedded")
class OutputSchemaApplicationTest {

    @Autowired private SchemaValidatorGuardrail guardrail;
    @Autowired private SkillRegistry            skillRegistry;

    private GuardrailOutputContext ctx(SkillMeta meta, String rawResponse, String schema) {
        Map<String, Object> attrs = schema == null ? Map.of() : Map.of("output_schema", schema);
        return new GuardrailOutputContext(rawResponse, "alice", "session-1", meta, attrs);
    }

    private SkillMeta meta(String name) {
        return skillRegistry.findMeta(name).orElseThrow();
    }

    // ── 1. SKILL.md round-trip ──────────────────────────────────────

    @Test
    @DisplayName("profile-skill SKILL.md frontmatter produces hasSchema=true and a non-null outputSchema")
    void profileSkillCarriesSchema() {
        SkillMeta m = meta("profile-skill");
        assertTrue(m.hasSchema(),
                "metadata.output-schema in frontmatter must set hasSchema=true");

        SkillCard card = skillRegistry.load("profile-skill");
        assertNotNull(card.outputSchema(),
                "SkillCard.outputSchema must contain the inline JSON schema");
        assertTrue(card.outputSchema().contains("\"required\""),
                "the inline schema text should round-trip verbatim, got: "
                        + card.outputSchema().substring(0, Math.min(60, card.outputSchema().length())));
    }

    @Test
    @DisplayName("default-skill has no schema — hasSchema=false")
    void defaultSkillHasNoSchema() {
        assertFalse(meta("default-skill").hasSchema());
    }

    // ── 2. PASS paths ──────────────────────────────────────────────

    @Test
    @DisplayName("Skill without schema → PASS unconditionally")
    void skillWithoutSchemaPasses() {
        GuardrailOutputResult r = guardrail.process(
                ctx(meta("default-skill"), "anything goes here, even prose", null));
        assertEquals(GuardrailVerdict.PASS, r.verdict());
    }

    @Test
    @DisplayName("Skill WITH schema + valid JSON → PASS")
    void validJsonPasses() {
        SkillCard card = skillRegistry.load("profile-skill");
        String json = "{\"name\":\"Alice\",\"age\":30,\"email\":\"alice@example.com\"}";
        GuardrailOutputResult r = guardrail.process(
                ctx(card.meta(), json, card.outputSchema()));
        assertEquals(GuardrailVerdict.PASS, r.verdict(),
                "valid JSON conforming to the schema must pass; reason=" + r.reason());
    }

    @Test
    @DisplayName("Skill WITH schema + non-JSON response → PASS with skipped-reason")
    void nonJsonResponsePasses() {
        SkillCard card = skillRegistry.load("profile-skill");
        GuardrailOutputResult r = guardrail.process(
                ctx(card.meta(), "Sorry, I couldn't extract that.", card.outputSchema()));
        assertEquals(GuardrailVerdict.PASS, r.verdict());
        assertNotNull(r.reason());
        assertTrue(r.reason().contains("does not contain JSON"),
                "expected the skipped-reason message; got: " + r.reason());
    }

    @Test
    @DisplayName("Markdown-wrapped JSON is extracted from ```json``` fences and validated")
    void markdownWrappedJsonIsExtracted() {
        SkillCard card = skillRegistry.load("profile-skill");
        String wrapped = """
                Here you go:
                ```json
                {"name":"Bob","age":25,"email":"bob@example.com"}
                ```
                Hope this helps!
                """;
        GuardrailOutputResult r = guardrail.process(
                ctx(card.meta(), wrapped, card.outputSchema()));
        assertEquals(GuardrailVerdict.PASS, r.verdict(),
                "JSON inside ```json``` fences must be extracted and validated; reason=" + r.reason());
    }

    @Test
    @DisplayName("Schema-bearing skill but no schema in inputAttributes → soft PASS (no exception)")
    void missingSchemaInAttributesIsSoftPass() {
        SkillMeta m = meta("profile-skill");
        GuardrailOutputResult r = guardrail.process(
                ctx(m, "{\"name\":\"x\"}", null));
        assertEquals(GuardrailVerdict.PASS, r.verdict(),
                "guardrail must fall back to PASS rather than throw when the orchestrator forgot to inject the schema");
    }

    // ── 3. BLOCK paths ─────────────────────────────────────────────

    @Test
    @DisplayName("Missing required field → BLOCK with the missing-required message in the reason")
    void missingRequiredFieldBlocks() {
        SkillCard card = skillRegistry.load("profile-skill");
        // "email" required by the schema is absent.
        String json = "{\"name\":\"Alice\",\"age\":30}";
        GuardrailOutputResult r = guardrail.process(
                ctx(card.meta(), json, card.outputSchema()));
        assertEquals(GuardrailVerdict.BLOCK, r.verdict());
        assertNotNull(r.reason());
        assertTrue(r.reason().contains("email"),
                "BLOCK reason should mention the missing 'email' field, got: " + r.reason());
    }

    @Test
    @DisplayName("Wrong type for a required field → BLOCK")
    void wrongTypeBlocks() {
        SkillCard card = skillRegistry.load("profile-skill");
        // age must be integer, not string
        String json = "{\"name\":\"Alice\",\"age\":\"thirty\",\"email\":\"a@b.c\"}";
        GuardrailOutputResult r = guardrail.process(
                ctx(card.meta(), json, card.outputSchema()));
        assertEquals(GuardrailVerdict.BLOCK, r.verdict());
        assertTrue(r.reason().contains("age") || r.reason().contains("integer"),
                "BLOCK reason should mention age / integer; got: " + r.reason());
    }

    @Test
    @DisplayName("Value out of declared range (age > 150) → BLOCK")
    void outOfRangeBlocks() {
        SkillCard card = skillRegistry.load("profile-skill");
        String json = "{\"name\":\"Methuselah\",\"age\":969,\"email\":\"m@example.com\"}";
        GuardrailOutputResult r = guardrail.process(
                ctx(card.meta(), json, card.outputSchema()));
        assertEquals(GuardrailVerdict.BLOCK, r.verdict());
    }

    @Test
    @DisplayName("additionalProperties=false → unknown fields BLOCK")
    void additionalPropertiesBlock() {
        SkillCard card = skillRegistry.load("profile-skill");
        String json = "{\"name\":\"Alice\",\"age\":30,\"email\":\"a@b.c\",\"unexpected\":1}";
        GuardrailOutputResult r = guardrail.process(
                ctx(card.meta(), json, card.outputSchema()));
        assertEquals(GuardrailVerdict.BLOCK, r.verdict());
    }
}
