package ai.gargantua.example.toolcache.tools;

import ai.gargantua.core.tool.AgentTool;
import ai.gargantua.core.tool.CacheScope;
import ai.gargantua.core.tool.CacheableToolResult;
import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Pure in-process tool whose every method increments a counter on each
 * <em>actual</em> invocation. Combined with {@code @CacheableToolResult},
 * the counter lets tests prove exactly how many times the tool body ran
 * — every cache hit is a no-op the counter doesn't see.
 *
 * <p>The five {@code @AgentTool} methods each isolate a different aspect
 * of the annotation:</p>
 *
 * <ol>
 *   <li>{@link #lookupGlobal(String)} — basic happy path: GLOBAL scope,
 *       single key parameter.</li>
 *   <li>{@link #lookupWithKeyParamSubset(String, String)} — declares
 *       {@code keyParams = {"ticker"}}; changes to {@code currency} are
 *       irrelevant to the cache key.</li>
 *   <li>{@link #lookupShortTtl(String)} — TTL of 1 s so tests can prove
 *       expiry without waiting forever.</li>
 *   <li>{@link #lookupForUser(String)} — USER scope: same category for
 *       two distinct users must miss the cache.</li>
 *   <li>{@link #lookupForSession(String)} — SESSION scope: same query
 *       in two distinct sessions must miss the cache.</li>
 * </ol>
 *
 * <p>{@link #reset()} clears every counter between tests.</p>
 */
@Component
public class CounterTool {

    private final ConcurrentMap<String, AtomicInteger> counters = new ConcurrentHashMap<>();

    /** Resets every counter. Call from {@code @BeforeEach} in tests. */
    public void reset() {
        counters.values().forEach(c -> c.set(0));
    }

    public int callsFor(String method) {
        return counters.computeIfAbsent(method, k -> new AtomicInteger()).get();
    }

    private int bump(String method) {
        return counters.computeIfAbsent(method, k -> new AtomicInteger()).incrementAndGet();
    }

    // ── 1. GLOBAL scope, single key parameter ───────────────────────

    @AgentTool(description = """
            Looks up a deterministic profile for the given ticker. The tool body
            increments a counter on every actual invocation, so repeated calls
            for the same ticker reveal whether the registry cached the result.
            """)
    @CacheableToolResult(
            ttlSeconds = 60,
            keyParams = {"ticker"},
            scope = CacheScope.GLOBAL
    )
    public String lookupGlobal(String ticker) {
        int n = bump("lookupGlobal");
        return "ticker=" + ticker + ";calls=" + n;
    }

    // ── 2. keyParams subset ─────────────────────────────────────────

    @AgentTool(description = """
            Looks up a profile keyed only on `ticker` — the `currency` parameter
            is intentionally NOT listed in keyParams, so changing it must hit
            the same cache slot as long as `ticker` stays the same.
            """)
    @CacheableToolResult(
            ttlSeconds = 60,
            keyParams = {"ticker"},
            scope = CacheScope.GLOBAL
    )
    public String lookupWithKeyParamSubset(String ticker, String currency) {
        int n = bump("lookupWithKeyParamSubset");
        return "ticker=" + ticker + ";currency=" + currency + ";calls=" + n;
    }

    // ── 3. Short TTL — expiry observable in 1 s ─────────────────────

    @AgentTool(description = """
            Looks up a profile with a 1-second TTL — used in tests to observe
            cache expiry without waiting tens of seconds.
            """)
    @CacheableToolResult(
            ttlSeconds = 1,
            keyParams = {"key"},
            scope = CacheScope.GLOBAL
    )
    public String lookupShortTtl(String key) {
        int n = bump("lookupShortTtl");
        return "key=" + key + ";calls=" + n;
    }

    // ── 4. USER scope ──────────────────────────────────────────────

    @AgentTool(description = """
            Looks up a per-user preference. With USER scope, two different
            users asking for the same category must each invoke the tool —
            the cache is keyed per (user, category).
            """)
    @CacheableToolResult(
            ttlSeconds = 60,
            keyParams = {"category"},
            scope = CacheScope.USER
    )
    public String lookupForUser(String category) {
        int n = bump("lookupForUser");
        return "category=" + category + ";calls=" + n;
    }

    // ── 5. SESSION scope ───────────────────────────────────────────

    @AgentTool(description = """
            Looks up a session-local search result. With SESSION scope, two
            different sessions asking the same query must each invoke the
            tool — the cache is keyed per (session, query).
            """)
    @CacheableToolResult(
            ttlSeconds = 60,
            keyParams = {"query"},
            scope = CacheScope.SESSION
    )
    public String lookupForSession(String query) {
        int n = bump("lookupForSession");
        return "query=" + query + ";calls=" + n;
    }
}
