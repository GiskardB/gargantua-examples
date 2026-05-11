package ai.gargantua.example.toolcache;

import ai.gargantua.autoconfigure.ToolRegistry;
import ai.gargantua.core.security.SecurityContext;
import ai.gargantua.core.tool.ToolExecutionContext;
import ai.gargantua.example.toolcache.tools.CounterTool;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Pins every observable branch of {@code @CacheableToolResult} end-to-end
 * against the in-memory cache backend wired by
 * {@code EmbeddedProfileAutoConfiguration} (framework v1.2.5+). No Redis,
 * no Mongo, no Internet — {@code mvn test} just works.
 *
 * <p>Each test method asserts one guarantee of the annotation:
 *
 * <ol>
 *   <li>Second call with the same args hits the cache — tool body runs once.</li>
 *   <li>Distinct args land in distinct cache slots — tool runs once per arg.</li>
 *   <li>{@code keyParams} subset: parameters NOT listed do not affect the
 *       cache key — different {@code currency} with same {@code ticker}
 *       still hits.</li>
 *   <li>Entry past its TTL is treated as missing — tool body runs again.</li>
 *   <li>{@code USER} scope isolates per user — same category for two users
 *       misses twice.</li>
 *   <li>{@code SESSION} scope isolates per session — same query in two
 *       sessions misses twice.</li>
 *   <li>USER scope without a SecurityContext skips the cache entirely
 *       (cannot build a key) — every call invokes the tool.</li>
 *   <li>Micrometer {@code agent.tool.cache.hits/misses} counters increment
 *       as expected.</li>
 * </ol>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@ActiveProfiles("embedded")
class ToolCacheApplicationTest {

    @Autowired private ToolRegistry  toolRegistry;
    @Autowired private CounterTool   counterTool;
    @Autowired private MeterRegistry meterRegistry;

    @BeforeEach
    void resetCounters() {
        counterTool.reset();
    }

    // ── 1. Basic hit ────────────────────────────────────────────────

    @Test
    @DisplayName("Second call with the same ticker returns cached value — tool body runs once")
    void secondCallHitsCache() {
        String first  = toolRegistry.executeTool("lookupGlobal", "{\"ticker\":\"AAPL\"}");
        String second = toolRegistry.executeTool("lookupGlobal", "{\"ticker\":\"AAPL\"}");

        assertEquals("ticker=AAPL;calls=1", first);
        assertEquals(first, second);
        assertEquals(1, counterTool.callsFor("lookupGlobal"),
                "tool body should run exactly once across two calls with the same key");
    }

    // ── 2. Distinct args → distinct slots ───────────────────────────

    @Test
    @DisplayName("Distinct ticker values land in distinct cache slots — tool runs once per ticker")
    void distinctArgsAreDistinctSlots() {
        toolRegistry.executeTool("lookupGlobal", "{\"ticker\":\"AAPL\"}");
        toolRegistry.executeTool("lookupGlobal", "{\"ticker\":\"MSFT\"}");
        toolRegistry.executeTool("lookupGlobal", "{\"ticker\":\"AAPL\"}"); // hit
        toolRegistry.executeTool("lookupGlobal", "{\"ticker\":\"MSFT\"}"); // hit

        assertEquals(2, counterTool.callsFor("lookupGlobal"),
                "AAPL and MSFT each run once; repeats hit the cache");
    }

    // ── 3. keyParams subset ─────────────────────────────────────────

    @Test
    @DisplayName("keyParams subset: a parameter not in keyParams is ignored for the cache key")
    void keyParamSubsetIgnoresExtras() {
        toolRegistry.executeTool("lookupWithKeyParamSubset",
                "{\"ticker\":\"AAPL\",\"currency\":\"USD\"}");
        // Same ticker, different currency — should still hit the cache.
        toolRegistry.executeTool("lookupWithKeyParamSubset",
                "{\"ticker\":\"AAPL\",\"currency\":\"EUR\"}");

        assertEquals(1, counterTool.callsFor("lookupWithKeyParamSubset"),
                "currency is not in keyParams — cache key is identical");
    }

    // ── 4. TTL expiry ───────────────────────────────────────────────

    @Test
    @DisplayName("Entry past its TTL is treated as missing — tool runs again after expiry")
    void ttlExpiryForcesReinvocation() throws InterruptedException {
        toolRegistry.executeTool("lookupShortTtl", "{\"key\":\"alpha\"}");
        toolRegistry.executeTool("lookupShortTtl", "{\"key\":\"alpha\"}"); // hit
        Thread.sleep(1_100); // ttlSeconds = 1
        toolRegistry.executeTool("lookupShortTtl", "{\"key\":\"alpha\"}"); // miss again

        assertEquals(2, counterTool.callsFor("lookupShortTtl"),
                "expected one invocation before expiry and one after — total 2");
    }

    // ── 5. USER scope isolates per user ─────────────────────────────

    @Test
    @DisplayName("USER scope: same category for two users misses the cache twice")
    void userScopeIsolatesPerUser() {
        var alice = ToolExecutionContext.of(
                new SecurityContext("alice", null, Set.of()), null);
        var bob   = ToolExecutionContext.of(
                new SecurityContext("bob",   null, Set.of()), null);

        toolRegistry.executeTool("lookupForUser", "{\"category\":\"news\"}", alice); // miss
        toolRegistry.executeTool("lookupForUser", "{\"category\":\"news\"}", bob);   // miss
        toolRegistry.executeTool("lookupForUser", "{\"category\":\"news\"}", alice); // hit
        toolRegistry.executeTool("lookupForUser", "{\"category\":\"news\"}", bob);   // hit

        assertEquals(2, counterTool.callsFor("lookupForUser"),
                "alice and bob have independent cache slots — 2 misses, 2 hits");
    }

    @Test
    @DisplayName("USER scope without a SecurityContext skips the cache — every call invokes the tool")
    void userScopeWithoutContextSkipsCache() {
        toolRegistry.executeTool("lookupForUser", "{\"category\":\"news\"}"); // empty context
        toolRegistry.executeTool("lookupForUser", "{\"category\":\"news\"}");

        assertEquals(2, counterTool.callsFor("lookupForUser"),
                "USER scope cannot build a key without a security context — cache is bypassed");
    }

    // ── 6. SESSION scope isolates per session ───────────────────────

    @Test
    @DisplayName("SESSION scope: same query in two sessions misses the cache twice")
    void sessionScopeIsolatesPerSession() {
        var s1 = ToolExecutionContext.of(null, "session-1");
        var s2 = ToolExecutionContext.of(null, "session-2");

        toolRegistry.executeTool("lookupForSession", "{\"query\":\"weather\"}", s1); // miss
        toolRegistry.executeTool("lookupForSession", "{\"query\":\"weather\"}", s2); // miss
        toolRegistry.executeTool("lookupForSession", "{\"query\":\"weather\"}", s1); // hit
        toolRegistry.executeTool("lookupForSession", "{\"query\":\"weather\"}", s2); // hit

        assertEquals(2, counterTool.callsFor("lookupForSession"));
    }

    // ── 7. Micrometer counters ──────────────────────────────────────

    @Test
    @DisplayName("Micrometer agent.tool.cache.{hits,misses} counters increment per scope")
    void hitMissCountersIncrement() {
        double beforeHits   = counter("agent.tool.cache.hits",   "tool", "lookupGlobal", "scope", "GLOBAL");
        double beforeMisses = counter("agent.tool.cache.misses", "tool", "lookupGlobal", "scope", "GLOBAL");

        toolRegistry.executeTool("lookupGlobal", "{\"ticker\":\"NFLX\"}"); // miss
        toolRegistry.executeTool("lookupGlobal", "{\"ticker\":\"NFLX\"}"); // hit
        toolRegistry.executeTool("lookupGlobal", "{\"ticker\":\"NFLX\"}"); // hit

        double afterHits   = counter("agent.tool.cache.hits",   "tool", "lookupGlobal", "scope", "GLOBAL");
        double afterMisses = counter("agent.tool.cache.misses", "tool", "lookupGlobal", "scope", "GLOBAL");

        assertEquals(1.0, afterMisses - beforeMisses, 0.0001);
        assertEquals(2.0, afterHits   - beforeHits,   0.0001);
    }

    // ── helper ──────────────────────────────────────────────────────

    private double counter(String name, String... tags) {
        var c = meterRegistry.find(name).tags(tags).counter();
        return c == null ? 0.0 : c.count();
    }
}
