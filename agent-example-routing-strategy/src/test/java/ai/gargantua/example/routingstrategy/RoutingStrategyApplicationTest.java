package ai.gargantua.example.routingstrategy;

import ai.gargantua.autoconfigure.AgentProperties;
import ai.gargantua.autoconfigure.SemanticRoutingService;
import ai.gargantua.core.orchestrator.RoutingMethod;
import ai.gargantua.core.orchestrator.RoutingResult;
import ai.gargantua.core.skill.SkillMeta;
import ai.gargantua.core.skill.SkillRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Pins the routing-strategy contract observable from
 * {@link SemanticRoutingService#route}:
 *
 * <ul>
 *   <li>strategy=semantic picks the skill whose description has the
 *       highest cosine similarity with the user message, and falls
 *       through to the configured fallback skill when nothing beats the
 *       threshold — still tagged {@code RoutingMethod.SEMANTIC}.</li>
 *   <li>strategy=hybrid behaves like semantic above the threshold, and
 *       only falls through to the LLM router below it (the LLM path
 *       requires a reachable model — see the LLM test below).</li>
 *   <li>strategy=llm sends every query to the routing model. In the
 *       test environment there is no Ollama server running, so the
 *       routing service catches the SocketException and falls back to
 *       the configured fallback skill — still tagged
 *       {@code RoutingMethod.LLM}. This pins the graceful-degradation
 *       guarantee.</li>
 * </ul>
 *
 * <p>The example's three skill descriptions (weather, finance, coding)
 * are intentionally disjoint so the semantic match is unambiguous for
 * realistic queries.</p>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@ActiveProfiles("embedded")
class RoutingStrategyApplicationTest {

    @Autowired private SemanticRoutingService routing;
    @Autowired private SkillRegistry          skillRegistry;
    @Autowired private AgentProperties        properties;

    private List<SkillMeta> skills;
    private String originalStrategy;

    @BeforeEach
    void seedSkills() {
        skills = skillRegistry.listMeta();
        // Embeddings are populated lazily on first route(); calling
        // index() up-front makes the assertions snappier and removes a
        // class of ordering surprises.
        routing.index(skills);
        originalStrategy = properties.getRouting().getStrategy();
    }

    @AfterEach
    void restoreStrategy() {
        properties.getRouting().setStrategy(originalStrategy);
    }

    // ── 1. semantic — happy path picks the obviously-matching skill ──

    @Test
    @DisplayName("semantic strategy: a weather query routes to weather-skill via SEMANTIC")
    void semanticRoutesWeatherQuery() {
        properties.getRouting().setStrategy("semantic");

        RoutingResult result = routing.route(
                "What is the weather forecast for Rome tomorrow?", skills);

        assertEquals("weather-skill", result.skillName());
        assertEquals(RoutingMethod.SEMANTIC, result.method());
        assertTrue(result.confidence() >= properties.getRouting().getSemantic().getThreshold(),
                "score should clear threshold: " + result);
    }

    @Test
    @DisplayName("semantic strategy: a finance query routes to finance-skill via SEMANTIC")
    void semanticRoutesFinanceQuery() {
        properties.getRouting().setStrategy("semantic");

        RoutingResult result = routing.route(
                "What is the current stock price of Apple and Microsoft?", skills);

        assertEquals("finance-skill", result.skillName());
        assertEquals(RoutingMethod.SEMANTIC, result.method());
    }

    @Test
    @DisplayName("semantic strategy: a coding query routes to coding-skill via SEMANTIC")
    void semanticRoutesCodingQuery() {
        properties.getRouting().setStrategy("semantic");

        RoutingResult result = routing.route(
                "I need help with software engineering — writing Python functions and reviewing code.",
                skills);

        assertEquals("coding-skill", result.skillName(),
                "got " + result + ", confidence " + result.confidence());
        assertEquals(RoutingMethod.SEMANTIC, result.method());
    }

    // ── 2. semantic — below threshold falls back to default-skill ──

    @Test
    @DisplayName("semantic strategy: low-similarity query falls back to default-skill, still SEMANTIC")
    void semanticBelowThresholdFallsBack() {
        // Crank the threshold up so even a strongly-matching query won't pass.
        properties.getRouting().setStrategy("semantic");
        double original = properties.getRouting().getSemantic().getThreshold();
        properties.getRouting().getSemantic().setThreshold(0.999);
        try {
            RoutingResult result = routing.route(
                    "asdf qwer zxcv 1234 random tokens that match nothing",
                    skills);

            assertEquals("default-skill", result.skillName(),
                    "below-threshold query should land on the configured fallback skill");
            assertEquals(RoutingMethod.SEMANTIC, result.method(),
                    "strategy=semantic must never report LLM, even on fallback");
        } finally {
            properties.getRouting().getSemantic().setThreshold(original);
        }
    }

    // ── 3. hybrid — above threshold behaves exactly like semantic ──

    @Test
    @DisplayName("hybrid strategy: a clearly-matching query stops at the semantic stage (no LLM call)")
    void hybridAboveThresholdStaysSemantic() {
        properties.getRouting().setStrategy("hybrid");

        RoutingResult result = routing.route(
                "What is the weather and temperature in Milan today?", skills);

        assertEquals("weather-skill", result.skillName());
        assertEquals(RoutingMethod.SEMANTIC, result.method(),
                "hybrid should NOT escalate to LLM when the semantic score is already confident");
    }

    // ── 4. llm strategy — graceful fallback when no LLM is reachable ──

    @Test
    @DisplayName("llm strategy with no reachable routing model: still returns fallback-skill (no exception)")
    void llmStrategyGracefulFallback() {
        properties.getRouting().setStrategy("llm");

        // The test environment has no Ollama server running, so the
        // ChatModel.chat() call throws and RoutingService catches it,
        // returning the configured fallback skill. Crucially, no
        // exception escapes route().
        RoutingResult result = assertDoesNotThrow(
                () -> routing.route("hello there", skills));

        assertEquals("default-skill", result.skillName());
        assertEquals(RoutingMethod.LLM, result.method(),
                "strategy=llm must still tag the result as LLM, even on fallback");
    }

    // ── 5. empty skill list short-circuits to the fallback ──

    @Test
    @DisplayName("empty skill list short-circuits to fallback-skill regardless of strategy")
    void emptySkillsShortCircuit() {
        properties.getRouting().setStrategy("semantic");

        RoutingResult result = routing.route("anything", List.of());

        assertEquals("default-skill", result.skillName());
        // No embedding was scored.
        assertEquals(0.0, result.confidence(), 0.0001);
    }
}
