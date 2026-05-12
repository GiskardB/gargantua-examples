package ai.gargantua.example.llmrouting;

import ai.gargantua.autoconfigure.AgentProperties;
import ai.gargantua.autoconfigure.LlmRouter;
import ai.gargantua.autoconfigure.LlmRouter.RoutingDecision;
import ai.gargantua.autoconfigure.LlmRouter.RuleEvaluation;
import ai.gargantua.core.llm.LlmRoutingContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.time.DayOfWeek;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Pins the LLM model-pool routing-rule grammar end-to-end against the
 * rule set declared in {@code application.yml}. Each test constructs an
 * {@link LlmRoutingContext} crafted to land on a specific rule by name,
 * so the assertions can match both the {@code selectedAlias} and the
 * {@code matchedRule} returned by {@link LlmRouter#evaluateAll}.
 *
 * <p>No LLM is invoked — the tests exercise routing logic in isolation.</p>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@ActiveProfiles("embedded")
class LlmRoutingApplicationTest {

    @Autowired private LlmRouter router;
    @Autowired private AgentProperties properties;

    // ─── helpers ───────────────────────────────────────────────────────

    private LlmRoutingContext neutral(String skill, String domain) {
        return new LlmRoutingContext(
                "alice", "session-1", skill, domain,
                "hello",  // userMessage
                5,        // inputLengthChars
                2,        // estimatedInputTokens
                "free",   // userTier
                LocalTime.of(12, 0),  // requestTime — noon, never night-hours
                DayOfWeek.MONDAY,     // requestDay — never weekend
                Map.of()
        );
    }

    private RuleEvaluation traceFor(RoutingDecision decision, String ruleName) {
        return decision.evaluatedRules().stream()
                .filter(r -> ruleName.equals(r.name()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("No trace entry for rule '" + ruleName + "'"));
    }

    // ─── 1. Fallback paths ─────────────────────────────────────────────

    @Test
    @DisplayName("No rule matches → primary-alias is returned, matchedRule is null")
    void noMatchFallsBackToPrimaryAlias() {
        var ctx = neutral("default-skill", "general");
        RoutingDecision decision = router.evaluateAll(ctx);

        assertEquals("default", decision.selectedAlias(),
                "Primary alias from agent.llm.primary-alias must win when no rule matches");
        assertNull(decision.matchedRule(), "matchedRule must be null on full pass-through");
        assertFalse(decision.evaluatedRules().isEmpty(), "Trace must still be populated");
    }

    @Test
    @DisplayName("Empty routing-rules list short-circuits to primary-alias with empty trace")
    void emptyRulesListReturnsPrimaryAlias() {
        var emptyProps = new AgentProperties();
        emptyProps.getLlm().setPrimaryAlias("test-primary");
        emptyProps.getLlm().setRoutingRules(new ArrayList<>());
        var emptyRouter = new LlmRouter(emptyProps);

        var ctx = neutral("any-skill", "any-domain");
        RoutingDecision decision = emptyRouter.evaluateAll(ctx);

        assertEquals("test-primary", decision.selectedAlias());
        assertNull(decision.matchedRule());
        assertTrue(decision.evaluatedRules().isEmpty(), "Empty rule list → empty trace");
    }

    // ─── 2. String operators ──────────────────────────────────────────

    @Test
    @DisplayName("String EQ: skill=coding-skill → coding-skill-eq rule wins")
    void skillEqMatchesActiveRule() {
        var ctx = neutral("coding-skill", "general");
        RoutingDecision decision = router.evaluateAll(ctx);

        assertEquals("coding-skill-eq", decision.matchedRule());
        assertEquals("model-fast", decision.selectedAlias());
    }

    @Test
    @DisplayName("Disabled rule is listed in the trace (enabled=false, matched=false) but never selected")
    void disabledRuleIsListedButSkipped() {
        var ctx = neutral("coding-skill", "general");
        RoutingDecision decision = router.evaluateAll(ctx);

        RuleEvaluation disabled = traceFor(decision, "disabled-coding-rule");
        assertFalse(disabled.enabled(), "disabled-coding-rule must report enabled=false");
        assertFalse(disabled.matched(), "matched must be false when enabled=false, even if condition would match");
        // The next rule by priority (coding-skill-eq) is the one that fires
        assertEquals("coding-skill-eq", decision.matchedRule());
    }

    @Test
    @DisplayName("String IN: domain=medical → medical-or-legal-domain-in rule wins")
    void domainInOperatorMatches() {
        var ctx = neutral("default-skill", "medical");
        RoutingDecision decision = router.evaluateAll(ctx);

        assertEquals("medical-or-legal-domain-in", decision.matchedRule());
        assertEquals("model-careful", decision.selectedAlias());
    }

    @Test
    @DisplayName("String NOT_IN: domain=robotics (not in the common-domains list) → niche-domain-not-in")
    void domainNotInOperatorMatches() {
        var ctx = neutral("default-skill", "robotics");
        RoutingDecision decision = router.evaluateAll(ctx);

        assertEquals("niche-domain-not-in", decision.matchedRule());
        assertEquals("model-specialty", decision.selectedAlias());
    }

    // ─── 3. Numeric operators ─────────────────────────────────────────

    @Test
    @DisplayName("Numeric GTE: input-length 600 ≥ 500 → long-input-gte rule wins")
    void inputLengthGteMatches() {
        var ctx = new LlmRoutingContext(
                "alice", "session-1", "default-skill", "general",
                "this would be a long prompt in production", 600, 150, "free",
                LocalTime.of(12, 0), DayOfWeek.MONDAY, Map.of());
        RoutingDecision decision = router.evaluateAll(ctx);

        assertEquals("long-input-gte", decision.matchedRule());
        assertEquals("model-cheap", decision.selectedAlias());
    }

    @Test
    @DisplayName("Legacy `min-tokens` shorthand: estimated-tokens=250 ≥ 200 → min-tokens-legacy rule wins")
    void legacyMinTokensGteMatches() {
        var ctx = new LlmRoutingContext(
                "alice", "session-1", "default-skill", "general",
                "short", 5, 250, "free",
                LocalTime.of(12, 0), DayOfWeek.MONDAY, Map.of());
        RoutingDecision decision = router.evaluateAll(ctx);

        assertEquals("min-tokens-legacy", decision.matchedRule());
        assertEquals("model-cheap-legacy", decision.selectedAlias());
    }

    // ─── 4. AND / OR combinators ──────────────────────────────────────

    @Test
    @DisplayName("AND combinator: user-tier=premium AND message contains TODO → premium-and-todo")
    void andCombinatorMatchesPremiumWithTodo() {
        var ctx = new LlmRoutingContext(
                "alice", "session-1", "default-skill", "general",
                "please fix this TODO in the parser", 35, 10, "premium",
                LocalTime.of(12, 0), DayOfWeek.MONDAY, Map.of());
        RoutingDecision decision = router.evaluateAll(ctx);

        assertEquals("premium-and-todo", decision.matchedRule());
        assertEquals("model-premium-code", decision.selectedAlias());
    }

    @Test
    @DisplayName("OR combinator: skill=metrics-skill matches first OR arm → or-skill-or-domain")
    void orCombinatorMatchesFirstArm() {
        var ctx = neutral("metrics-skill", "general");
        RoutingDecision decision = router.evaluateAll(ctx);

        assertEquals("or-skill-or-domain", decision.matchedRule());
        assertEquals("model-or-target", decision.selectedAlias());
    }

    // ─── 5. Time / day / sampling ─────────────────────────────────────

    @Test
    @DisplayName("time-window crossing midnight: 23:30 is inside 22:00→06:00 → night-hours rule wins")
    void timeWindowCrossesMidnight() {
        var ctx = new LlmRoutingContext(
                "alice", "session-1", "default-skill", "general",
                "hello", 5, 2, "free",
                LocalTime.of(23, 30), DayOfWeek.MONDAY, Map.of());
        RoutingDecision decision = router.evaluateAll(ctx);

        assertEquals("night-hours", decision.matchedRule());
        assertEquals("model-night", decision.selectedAlias());
    }

    @Test
    @DisplayName("day-of-week: requestDay=SATURDAY matches weekend-day rule")
    void dayOfWeekSaturdayMatches() {
        var ctx = new LlmRoutingContext(
                "alice", "session-1", "default-skill", "general",
                "hello", 5, 2, "free",
                LocalTime.of(12, 0), DayOfWeek.SATURDAY, Map.of());
        RoutingDecision decision = router.evaluateAll(ctx);

        assertEquals("weekend-day", decision.matchedRule());
        assertEquals("model-weekend", decision.selectedAlias());
    }

    @Test
    @DisplayName("random-sampling@100% AND-ed with skill gate → always picks the canary rule")
    void randomSampling100AlwaysMatchesForCanarySkill() {
        var ctx = neutral("canary-skill", "general");
        RoutingDecision decision = router.evaluateAll(ctx);

        assertEquals("canary-sampled-100", decision.matchedRule());
        assertEquals("model-canary", decision.selectedAlias());
    }

    // ─── 6. attribute-match REGEX ─────────────────────────────────────

    @Test
    @DisplayName("attribute-match REGEX: x-priority=urgent matches ^(high|urgent)$ → priority-header-regex")
    void attributeRegexMatches() {
        var ctx = new LlmRoutingContext(
                "alice", "session-1", "default-skill", "general",
                "hello", 5, 2, "free",
                LocalTime.of(12, 0), DayOfWeek.MONDAY,
                Map.of("x-priority", "urgent"));
        RoutingDecision decision = router.evaluateAll(ctx);

        assertEquals("priority-header-regex", decision.matchedRule());
        assertEquals("model-priority", decision.selectedAlias());
    }

    // ─── 7. Legacy attribute-equality fallback ────────────────────────

    @Test
    @DisplayName("Unknown condition key falls back to attribute equality (legacy syntax) → legacy-tenant-eq")
    void legacyUnknownKeyAttributeFallback() {
        var ctx = new LlmRoutingContext(
                "alice", "session-1", "default-skill", "general",
                "hello", 5, 2, "free",
                LocalTime.of(12, 0), DayOfWeek.MONDAY,
                Map.of("tenant-id", "gold-corp"));
        RoutingDecision decision = router.evaluateAll(ctx);

        assertEquals("legacy-tenant-eq", decision.matchedRule());
        assertEquals("model-tenant-gold", decision.selectedAlias());
    }

    // ─── 8. Priority ordering ─────────────────────────────────────────

    @Test
    @DisplayName("Priority ordering: when two rules match, the lower-priority one wins (coding-skill-eq beats long-input-gte)")
    void priorityLowerWins() {
        // skill=coding-skill matches priority=10 AND input-length=600 matches priority=25.
        // The trace should mark BOTH as matched, but matchedRule must be the priority=10 one.
        var ctx = new LlmRoutingContext(
                "alice", "session-1", "coding-skill", "general",
                "this would be a long prompt", 600, 150, "free",
                LocalTime.of(12, 0), DayOfWeek.MONDAY, Map.of());
        RoutingDecision decision = router.evaluateAll(ctx);

        assertEquals("coding-skill-eq", decision.matchedRule(), "Lower priority wins");
        assertEquals("model-fast", decision.selectedAlias());

        assertTrue(traceFor(decision, "coding-skill-eq").matched(),
                "Trace must show coding-skill-eq matched");
        assertTrue(traceFor(decision, "long-input-gte").matched(),
                "Trace must also show long-input-gte matched, even though it wasn't selected");
    }

    // ─── 9. Configuration sanity ──────────────────────────────────────

    @Test
    @DisplayName("All 13 routing rules from application.yml are loaded into AgentProperties and visible to the router")
    void allRulesAreLoaded() {
        assertEquals(13, properties.getLlm().getRoutingRules().size(),
                "application.yml declares 13 routing rules");

        // findRule sanity-checks the lookup API
        assertTrue(router.findRule("coding-skill-eq").isPresent());
        assertTrue(router.findRule("does-not-exist").isEmpty());
    }

}
