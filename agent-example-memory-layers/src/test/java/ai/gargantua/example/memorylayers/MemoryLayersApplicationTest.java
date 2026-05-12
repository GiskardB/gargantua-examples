package ai.gargantua.example.memorylayers;

import ai.gargantua.core.memory.ChatMessage;
import ai.gargantua.core.memory.ComposedMemory;
import ai.gargantua.core.memory.EpisodicMemoryPort;
import ai.gargantua.core.memory.KnowledgeMemoryPort;
import ai.gargantua.core.memory.KnowledgeSegment;
import ai.gargantua.core.memory.MemoryLayer;
import ai.gargantua.core.memory.SessionSummary;
import ai.gargantua.core.memory.WorkingMemoryPort;
import ai.gargantua.core.skill.SkillCard;
import ai.gargantua.core.skill.SkillRegistry;
import ai.gargantua.memory.composer.MemoryComposer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Pins the three-layer memory subsystem against the in-memory adapters
 * wired by {@code EmbeddedProfileAutoConfiguration}: working
 * (session-scoped), episodic (compressed past sessions), knowledge
 * (persistent user facts), and the {@link MemoryComposer} that merges
 * them with token-budget truncation and skill-driven layer opt-out.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@ActiveProfiles("embedded")
class MemoryLayersApplicationTest {

    @Autowired private WorkingMemoryPort   working;
    @Autowired private EpisodicMemoryPort  episodic;
    @Autowired private KnowledgeMemoryPort knowledge;
    @Autowired private MemoryComposer      composer;
    @Autowired private SkillRegistry       skillRegistry;

    /** Per-test unique ids so cross-test pollution can't taint the assertions. */
    private String userId;
    private String sessionId;

    @BeforeEach
    void freshIds() {
        userId    = "user-"    + UUID.randomUUID();
        sessionId = "session-" + UUID.randomUUID();
    }

    // ── 1. WorkingMemoryPort round-trip ─────────────────────────────

    @Test
    @DisplayName("Working memory: appendMessage + getMessages round-trip in chronological order")
    void workingMemoryRoundTrip() {
        working.appendMessage(sessionId, ChatMessage.userMessage("hi"));
        working.appendMessage(sessionId, ChatMessage.assistantMessage("hello"));
        working.appendMessage(sessionId, ChatMessage.userMessage("how are you?"));

        List<ChatMessage> msgs = working.getMessages(sessionId);
        assertEquals(3, msgs.size());
        assertEquals("hi",            msgs.get(0).content());
        assertEquals("hello",         msgs.get(1).content());
        assertEquals("how are you?",  msgs.get(2).content());
        assertEquals("user",          msgs.get(0).role());
        assertEquals("assistant",     msgs.get(1).role());
    }

    @Test
    @DisplayName("Working memory: clear() empties the session, getMessages returns []")
    void workingMemoryClear() {
        working.appendMessage(sessionId, ChatMessage.userMessage("hi"));
        working.clear(sessionId);
        assertTrue(working.getMessages(sessionId).isEmpty());
    }

    // ── 2. EpisodicMemoryPort round-trip ───────────────────────────

    @Test
    @DisplayName("Episodic memory: getRecentSummaries returns saved summaries newest-first, capped by limit")
    void episodicMemoryRoundTrip() {
        Instant t0 = Instant.now().minusSeconds(300);
        Instant t1 = Instant.now().minusSeconds(200);
        Instant t2 = Instant.now().minusSeconds(100);

        episodic.saveSummary(new SessionSummary(userId, "s-old",  "oldest summary",  List.of("topic-a"), List.of(), 10, t0));
        episodic.saveSummary(new SessionSummary(userId, "s-mid",  "middle summary",  List.of("topic-b"), List.of(), 12, t1));
        episodic.saveSummary(new SessionSummary(userId, "s-new",  "newest summary",  List.of("topic-c"), List.of(), 15, t2));

        List<SessionSummary> top2 = episodic.getRecentSummaries(userId, 2);
        assertEquals(2, top2.size(), "limit must cap the returned list");
        assertEquals("newest summary", top2.get(0).summary(),
                "expected newest-first ordering");
        assertEquals("middle summary", top2.get(1).summary());
    }

    // ── 3. KnowledgeMemoryPort round-trip ──────────────────────────

    @Test
    @DisplayName("Knowledge memory: upsert / getSegments / delete round-trip")
    void knowledgeMemoryRoundTrip() {
        knowledge.upsertSegment(userId, "preferences", "prefers concise replies");
        knowledge.upsertSegment(userId, "profile",     "works in finance");

        List<KnowledgeSegment> all = knowledge.getSegments(userId);
        assertEquals(2, all.size());
        assertTrue(all.stream().anyMatch(s -> s.segmentKey().equals("preferences")
                && s.content().equals("prefers concise replies")));

        // Update existing key
        knowledge.upsertSegment(userId, "preferences", "prefers verbose replies");
        KnowledgeSegment updated = knowledge.getSegments(userId).stream()
                .filter(s -> s.segmentKey().equals("preferences"))
                .findFirst().orElseThrow();
        assertEquals("prefers verbose replies", updated.content(),
                "upsert must overwrite content for the same key");

        // Delete
        knowledge.deleteSegment(userId, "preferences");
        assertEquals(1, knowledge.getSegments(userId).size());
    }

    // ── 4. MemoryComposer with all layers (default) ────────────────

    @Test
    @DisplayName("Composer with all layers fetches working + episodic + knowledge in parallel")
    void composerFetchesAllLayers() {
        working.appendMessage(sessionId, ChatMessage.userMessage("ciao"));
        episodic.saveSummary(new SessionSummary(userId, sessionId, "earlier session about RAG",
                List.of("rag"), List.of(), 8, Instant.now().minusSeconds(60)));
        knowledge.upsertSegment(userId, "language", "prefers Italian");

        ComposedMemory mem = composer.compose(userId, sessionId, 10_000);

        assertEquals(1, mem.workingMessages().size());
        assertEquals(1, mem.episodicSummaries().size());
        assertEquals(1, mem.knowledgeSegments().size());
        assertTrue(mem.estimatedTokens() > 0);
    }

    // ── 5. enabledLayers subset — skipped ports are not called ─────

    @Test
    @DisplayName("Composer with WORKING-only fetches no episodic / knowledge — empty lists")
    void composerSkipsDisabledLayers() {
        working.appendMessage(sessionId, ChatMessage.userMessage("ciao"));
        episodic.saveSummary(new SessionSummary(userId, sessionId, "should not appear",
                List.of(), List.of(), 5, Instant.now()));
        knowledge.upsertSegment(userId, "profile", "should not appear either");

        ComposedMemory mem = composer.compose(userId, sessionId, 10_000,
                EnumSet.of(MemoryLayer.WORKING));

        assertEquals(1, mem.workingMessages().size(),
                "working layer should still be fetched");
        assertTrue(mem.episodicSummaries().isEmpty(),
                "episodic layer was disabled — must be empty");
        assertTrue(mem.knowledgeSegments().isEmpty(),
                "knowledge layer was disabled — must be empty");
    }

    // ── 6. Token-budget truncation ─────────────────────────────────

    @Test
    @DisplayName("Composer truncates knowledge first when over budget, episodic only after knowledge is exhausted")
    void composerTruncatesKnowledgeFirst() {
        // Working: keep tiny so it isn't trimmed (~1 token).
        working.appendMessage(sessionId, ChatMessage.userMessage("hi"));

        // Episodic: 2 summaries totalling ~50 tokens (100 chars / 4 ≈ 25 each).
        String episodicText = "x".repeat(100);
        episodic.saveSummary(new SessionSummary(userId, "s1", episodicText,
                List.of(), List.of(), 1, Instant.now().minusSeconds(60)));
        episodic.saveSummary(new SessionSummary(userId, "s2", episodicText,
                List.of(), List.of(), 1, Instant.now().minusSeconds(30)));

        // Knowledge: 4 segments × 200 chars = ~50 tokens each, ~200 total.
        for (int i = 1; i <= 4; i++) {
            knowledge.upsertSegment(userId, "k" + i, "y".repeat(200));
        }

        // Raw ≈ 1 (working) + 50 (episodic) + 200 (knowledge) = ~251 tokens.
        // Budget 100 forces dropping ~150 tokens — all reachable from knowledge
        // alone, so episodic stays intact.
        ComposedMemory mem = composer.compose(userId, sessionId, 100);

        assertTrue(mem.knowledgeSegments().size() < 4,
                "expected knowledge to be trimmed, got " + mem.knowledgeSegments().size());
        assertEquals(2, mem.episodicSummaries().size(),
                "episodic should be intact while knowledge can still be trimmed");
        assertTrue(mem.estimatedTokens() <= 100,
                "estimatedTokens must be within budget, got " + mem.estimatedTokens());
    }

    @Test
    @DisplayName("Composer truncates episodic when knowledge alone is not enough to fit the budget")
    void composerTruncatesEpisodicAfterKnowledge() {
        working.appendMessage(sessionId, ChatMessage.userMessage("hi"));

        // Heavy episodic + heavy knowledge. Budget too small for both.
        episodic.saveSummary(new SessionSummary(userId, "s1", "e".repeat(400),
                List.of(), List.of(), 1, Instant.now().minusSeconds(60)));
        episodic.saveSummary(new SessionSummary(userId, "s2", "e".repeat(400),
                List.of(), List.of(), 1, Instant.now().minusSeconds(30)));
        knowledge.upsertSegment(userId, "k1", "k".repeat(400));

        ComposedMemory mem = composer.compose(userId, sessionId, 50);

        assertTrue(mem.knowledgeSegments().isEmpty(),
                "knowledge must be exhausted first");
        assertTrue(mem.episodicSummaries().size() < 2,
                "episodic must also be trimmed when knowledge alone isn't enough; got "
                        + mem.episodicSummaries().size());
    }

    // ── 7. SKILL.md memory-layers round-trip ───────────────────────

    @Test
    @DisplayName("SKILL.md memory-layers: declared as [working] → SkillCard.enabledMemoryLayers contains only WORKING")
    void skillCardEnabledLayersFromFrontmatter() {
        SkillCard card = skillRegistry.load("greeter-skill");
        assertNotNull(card.enabledMemoryLayers(),
                "explicit memory-layers must produce a non-null set; null means 'all layers'");
        assertEquals(1, card.enabledMemoryLayers().size());
        assertTrue(card.enabledMemoryLayers().contains(MemoryLayer.WORKING));
    }

    @Test
    @DisplayName("SKILL.md without memory-layers: SkillCard.enabledMemoryLayers is null = composer fetches all layers")
    void skillCardNullEnabledLayersMeansAll() {
        SkillCard card = skillRegistry.load("assistant-skill");
        assertNull(card.enabledMemoryLayers(),
                "absent memory-layers must leave the field null so the composer defaults to all");
    }
}
