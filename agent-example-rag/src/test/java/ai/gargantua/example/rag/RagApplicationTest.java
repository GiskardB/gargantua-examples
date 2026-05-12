package ai.gargantua.example.rag;

import ai.gargantua.autoconfigure.RagEnricher;
import ai.gargantua.core.orchestrator.EnricherContext;
import ai.gargantua.core.rag.RagConfig;
import ai.gargantua.core.rag.RetrievedChunk;
import ai.gargantua.core.rag.VectorStorePort;
import ai.gargantua.core.skill.SkillCard;
import ai.gargantua.core.skill.SkillRegistry;
import ai.gargantua.example.rag.seed.KnowledgeBaseSeeder;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Pins the RAG contract end-to-end against the in-memory vector store
 * wired by {@code EmbeddedProfileAutoConfiguration}: SKILL.md frontmatter
 * → {@link RagConfig}, {@link VectorStorePort#search} → ranked chunks,
 * {@link RagEnricher} → {@code RELEVANT_DOCUMENTS}-shaped section in the
 * system prompt (or {@code null} when there's nothing to inject).
 *
 * <p>The {@link KnowledgeBaseSeeder} pre-populates the {@code support-faq}
 * collection at boot with four policy snippets — the assertions exercise
 * matches against each of them.</p>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@ActiveProfiles("embedded")
class RagApplicationTest {

    @Autowired private SkillRegistry   skillRegistry;
    @Autowired private VectorStorePort vectorStore;

    /*
     * Open framework gap (as of v1.2.7): RagAutoConfiguration declares
     * @ConditionalOnBean({VectorStorePort.class, SkillRegistry.class}) but
     * lacks @AutoConfigureAfter(EmbeddedProfileAutoConfiguration.class),
     * so the conditional is evaluated before the embedded vector-store bean
     * is registered and the framework never instantiates the RagEnricher.
     * The example tracks this in its README + the per-feature progress memory;
     * for now we instantiate the enricher directly so the contract can be
     * pinned against the same in-memory backend the framework would use.
     */
    private RagEnricher ragEnricher() {
        return new RagEnricher(vectorStore, skillRegistry);
    }

    private EnricherContext ctx(String skillName, String userMessage) {
        return new EnricherContext(
                "alice", "session-1", skillName, "support",
                userMessage, Map.of());
    }

    // ── 1. RagConfig round-trip from SKILL.md ──────────────────────

    @Test
    @DisplayName("support-skill SKILL.md frontmatter produces a populated RagConfig")
    void supportSkillHasRagConfig() {
        SkillCard card = skillRegistry.load("support-skill");
        RagConfig rag = card.ragConfig();
        assertNotNull(rag, "knowledge-base in metadata must produce a RagConfig");
        assertEquals(KnowledgeBaseSeeder.COLLECTION, rag.knowledgeBase());
        assertEquals(3, rag.maxResults());
        assertEquals(0.05, rag.minScore(), 0.0001);
    }

    @Test
    @DisplayName("default-skill SKILL.md has no knowledge-base — RagConfig is null")
    void defaultSkillHasNoRagConfig() {
        SkillCard card = skillRegistry.load("default-skill");
        assertNull(card.ragConfig(),
                "absent knowledge-base must produce ragConfig() == null (zero overhead)");
    }

    // ── 2. VectorStorePort.search behaviour ────────────────────────

    @Test
    @DisplayName("search returns chunks sorted by score (descending)")
    void searchReturnsRankedChunks() {
        List<RetrievedChunk> chunks = vectorStore.search(
                KnowledgeBaseSeeder.COLLECTION, "refund money back", 5, 0.0);
        assertFalse(chunks.isEmpty(), "expected at least one match");
        for (int i = 1; i < chunks.size(); i++) {
            assertTrue(chunks.get(i - 1).score() >= chunks.get(i).score(),
                    "chunks must be sorted by score desc; index " + i + " breaks the order: " + chunks);
        }
        // Top hit must be the refund-policy snippet — that's the source the seeder used.
        assertEquals("refund-policy.md", chunks.get(0).source(),
                "top hit for a refund query should be refund-policy.md, got: " + chunks);
    }

    @Test
    @DisplayName("search respects maxResults")
    void searchRespectsMaxResults() {
        List<RetrievedChunk> chunks = vectorStore.search(
                KnowledgeBaseSeeder.COLLECTION, "policy SLA refund maintenance password", 2, 0.0);
        assertEquals(2, chunks.size(),
                "maxResults=2 must cap the returned list at 2, got: " + chunks.size());
    }

    @Test
    @DisplayName("search respects minScore — chunks below threshold are dropped")
    void searchRespectsMinScore() {
        // A 0.99 minScore is essentially impossible to clear with Jaccard token similarity
        // for any of our seeded chunks vs. a realistic query.
        List<RetrievedChunk> chunks = vectorStore.search(
                KnowledgeBaseSeeder.COLLECTION, "refund policy", 10, 0.99);
        assertTrue(chunks.isEmpty(),
                "chunks below minScore must be dropped, got: " + chunks);
    }

    @Test
    @DisplayName("search on a missing collection returns an empty list (no exception)")
    void searchMissingCollectionReturnsEmpty() {
        List<RetrievedChunk> chunks = vectorStore.search(
                "no-such-collection", "anything", 5, 0.0);
        assertTrue(chunks.isEmpty());
    }

    // ── 3. RagEnricher behaviour ───────────────────────────────────

    @Test
    @DisplayName("enricher injects a RELEVANT_DOCUMENTS section for skills with a RagConfig")
    void enricherProducesSectionForRagSkill() {
        String section = ragEnricher().enrich(
                ctx("support-skill", "How do I get a refund on my last payment?"));

        assertNotNull(section, "enricher must return a non-null section for ragConfig'd skills");
        assertTrue(section.contains("Refund"),
                "the matched chunk's content should appear in the section: " + section);
        assertTrue(section.contains("refund-policy.md"),
                "the source attribution should appear: " + section);
        assertTrue(section.contains("Score:"),
                "the score should appear with each chunk: " + section);
    }

    @Test
    @DisplayName("enricher returns null for skills without a RagConfig (zero prompt overhead)")
    void enricherReturnsNullForNonRagSkill() {
        String section = ragEnricher().enrich(
                ctx("default-skill", "Hello, can you help me?"));
        assertNull(section, "no ragConfig → no enrichment, the prompt stays the same");
    }

    @Test
    @DisplayName("enricher returns null when no chunk clears the skill's minScore (irrelevant query)")
    void enricherReturnsNullWhenNothingMatches() {
        // Query has zero token overlap with any seeded chunk — Jaccard score = 0.
        String section = ragEnricher().enrich(
                ctx("support-skill", "asdf qwer zxcv 12345"));
        assertNull(section,
                "when the search returns no chunks, the enricher must return null, not an empty section");
    }

    @Test
    @DisplayName("enricher honours the per-skill maxResults — at most N entries in the section")
    void enricherHonoursMaxResults() {
        // The query overlaps with multiple seeded chunks (refund/SLA/password/maintenance).
        // support-skill declares rag-max-results=3, so at most 3 numbered entries should
        // appear. The seeder only writes 4 chunks total — perfect to verify the cap.
        String section = ragEnricher().enrich(
                ctx("support-skill", "policy refund SLA password reset maintenance"));
        assertNotNull(section);

        long numberedLines = section.lines()
                .filter(line -> line.matches("^\\d+\\. \\[Source:.*"))
                .count();
        assertTrue(numberedLines >= 1 && numberedLines <= 3,
                "expected between 1 and 3 numbered entries (rag-max-results=3); got " + numberedLines
                        + " in section:\n" + section);
    }
}
