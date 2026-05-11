package ai.gargantua.example.skillfilesystem;

import ai.gargantua.core.memory.MemoryLayer;
import ai.gargantua.core.skill.SkillCard;
import ai.gargantua.core.skill.SkillMeta;
import ai.gargantua.core.skill.SkillRegistry;
import ai.gargantua.core.skill.SkillSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Pins every observable branch of the SKILL.md filesystem parser, through
 * the public {@link SkillRegistry} contract.
 *
 * <p>The example ships four skills under {@code src/main/resources/skills/}:
 * {@code market-skill} (every frontmatter field populated),
 * {@code support-skill} (uses the {@code references/} folder),
 * {@code inactive-skill} ({@code metadata.active: false}), and
 * {@code default-skill} (greeter).</p>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@ActiveProfiles("embedded")
class SkillFilesystemApplicationTest {

    @Autowired private SkillRegistry skillRegistry;

    // ── 1. Discovery ────────────────────────────────────────────────

    @Test
    @DisplayName("All four SKILL.md files are discovered by the filesystem scan")
    void allFourSkillsDiscovered() {
        List<String> names = skillRegistry.listMeta().stream().map(SkillMeta::name).toList();
        assertTrue(names.contains("market-skill"),   "market-skill should be discovered: " + names);
        assertTrue(names.contains("support-skill"),  "support-skill should be discovered: " + names);
        assertTrue(names.contains("inactive-skill"), "inactive-skill should be discovered: " + names);
        assertTrue(names.contains("default-skill"),  "default-skill should be discovered: " + names);
    }

    // ── 2. SkillMeta round-trip (market-skill — the "everything-on" one) ──

    @Test
    @DisplayName("Frontmatter fields (name, description, version) round-trip into SkillMeta")
    void marketSkillCoreMeta() {
        SkillMeta meta = skillRegistry.findMeta("market-skill").orElseThrow();
        assertEquals("market-skill", meta.name());
        assertEquals("2.1.0", meta.version());
        assertTrue(meta.description().contains("Market analyst skill"),
                "description should round-trip: " + meta.description());
        assertEquals(SkillSource.FILESYSTEM, meta.source());
        assertTrue(meta.active());
    }

    @Test
    @DisplayName("metadata.domain and metadata.allowed-roles round-trip into SkillMeta")
    void marketSkillRoleAndDomain() {
        SkillMeta meta = skillRegistry.findMeta("market-skill").orElseThrow();
        assertEquals("market", meta.domain());
        assertEquals(Set.of("analyst", "trader"), meta.allowedRoles());
    }

    // ── 3. SkillCard fields populated by load() ─────────────────────

    @Test
    @DisplayName("allowed-tools YAML list parses into SkillCard.allowedTools (lookup + analyze)")
    void marketSkillAllowedTools() {
        SkillCard card = skillRegistry.load("market-skill");
        assertEquals(List.of("lookup", "analyze"), card.allowedTools());
    }

    @Test
    @DisplayName("Markdown body becomes SkillCard.systemPrompt, frontmatter is stripped")
    void marketSkillSystemPromptIsBody() {
        SkillCard card = skillRegistry.load("market-skill");
        assertTrue(card.systemPrompt().startsWith("## Role"),
                "body should start with '## Role', got: " + card.systemPrompt().substring(0, Math.min(40, card.systemPrompt().length())));
        assertTrue(card.systemPrompt().contains("careful market analyst"));
        assertFalse(card.systemPrompt().contains("---"),
                "frontmatter delimiters should not leak into the prompt body");
    }

    @Test
    @DisplayName("metadata overrides for temperature / max-tokens / preferred-model propagate")
    void marketSkillLlmOverrides() {
        SkillCard card = skillRegistry.load("market-skill");
        assertEquals(0.2, card.temperature(), 0.0001);
        assertEquals(800, card.maxTokens());
        assertEquals("primary", card.preferredModel());
    }

    @Test
    @DisplayName("metadata.memory-layers parses into the enum set (working + knowledge, NOT episodic)")
    void marketSkillMemoryLayers() {
        SkillCard card = skillRegistry.load("market-skill");
        assertNotNull(card.enabledMemoryLayers(),
                "non-empty memory-layers should produce a non-null set (null means 'all layers')");
        assertTrue(card.enabledMemoryLayers().contains(MemoryLayer.WORKING));
        assertTrue(card.enabledMemoryLayers().contains(MemoryLayer.KNOWLEDGE));
        assertFalse(card.enabledMemoryLayers().contains(MemoryLayer.EPISODIC),
                "EPISODIC was not listed in the YAML and must not appear");
    }

    @Test
    @DisplayName("metadata.knowledge-base + rag-* fields produce a RagConfig")
    void marketSkillRagConfig() {
        SkillCard card = skillRegistry.load("market-skill");
        assertNotNull(card.ragConfig(),
                "knowledge-base is set so ragConfig should not be null");
        assertEquals("market-rag", card.ragConfig().knowledgeBase());
        assertEquals(8, card.ragConfig().maxResults());
        assertEquals(0.4, card.ragConfig().minScore(), 0.0001);
    }

    @Test
    @DisplayName("Top-level frontmatter `examples` list propagates into the SkillCard")
    void marketSkillExamples() {
        SkillCard card = skillRegistry.load("market-skill");
        assertEquals(2, card.examples().size());
        assertTrue(card.examples().get(0).startsWith("Look up AAPL"));
    }

    @Test
    @DisplayName("Top-level frontmatter `references` list propagates into the SkillCard")
    void marketSkillFrontmatterReferences() {
        SkillCard card = skillRegistry.load("market-skill");
        assertEquals(2, card.references().size(),
                "market-skill has no references/ folder — only the 2 frontmatter entries should appear");
        assertTrue(card.references().get(0).contains("Earnings reports"));
    }

    // ── 4. references/ folder auto-append ──────────────────────────

    @Test
    @DisplayName("references/ folder contents are auto-appended to SkillCard.references on load()")
    void supportSkillFolderReferences() {
        SkillCard card = skillRegistry.load("support-skill");
        assertEquals(2, card.references().size(),
                "support-skill has no frontmatter references; the 2 folder files should fill the list");

        String joined = String.join("\n", card.references());
        assertTrue(joined.contains("Refund policy"),
                "refund-policy.md content should be appended: " + joined);
        assertTrue(joined.contains("Service-level agreement"),
                "sla.md content should be appended: " + joined);
    }

    // ── 5. metadata.active = false ─────────────────────────────────

    @Test
    @DisplayName("metadata.active: false is reflected in SkillMeta.active() (skill still listed, but inactive)")
    void inactiveSkillIsListedButInactive() {
        SkillMeta meta = skillRegistry.findMeta("inactive-skill").orElseThrow();
        assertFalse(meta.active(),
                "metadata.active: false must round-trip into SkillMeta.active() == false");
    }
}
