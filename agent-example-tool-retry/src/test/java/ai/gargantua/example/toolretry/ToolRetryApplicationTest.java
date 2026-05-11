package ai.gargantua.example.toolretry;

import ai.gargantua.autoconfigure.ToolRegistry;
import ai.gargantua.example.toolretry.tools.FlakyTool;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifies every branch of the {@code @ToolRetry} contract end-to-end without
 * booting an LLM, MongoDB or Redis.
 *
 * <p>The {@link FlakyTool} bean exposes four {@code @AgentTool} methods, one
 * per scenario:</p>
 * <ol>
 *   <li>Eventually succeeds — proves retries are transparent on the happy path.</li>
 *   <li>Always fails on a retried exception — proves the retry budget is
 *       respected and exhaustion returns structured JSON.</li>
 *   <li>Throws an exception not in {@code retryOn} — proves the predicate
 *       filter; the tool runs once, no retries.</li>
 *   <li>Throws an exception listed in {@code abortOn} (with a wide
 *       {@code retryOn} that would otherwise match) — proves abort wins.</li>
 * </ol>
 *
 * <p>Run with {@code mvn test}. No Docker, no API keys, no Internet.</p>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@ActiveProfiles("embedded")
class ToolRetryApplicationTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Autowired private ToolRegistry  toolRegistry;
    @Autowired private FlakyTool     flakyTool;
    @Autowired private MeterRegistry meterRegistry;

    @BeforeEach
    void resetTool() {
        flakyTool.reset();
    }

    // ── Wiring ──────────────────────────────────────────────────────

    @Test
    @DisplayName("All four @AgentTool methods register, by the names the LLM sees")
    void allFourToolsAreRegistered() {
        var names = toolRegistry.getAllToolNames();
        assertTrue(names.contains("flakyAdd"));
        assertTrue(names.contains("alwaysFail"));
        assertTrue(names.contains("failWithNonRetriedException"));
        assertTrue(names.contains("failWithAbortException"));
    }

    // ── 1. Eventually succeeds ──────────────────────────────────────

    @Test
    @DisplayName("flakyAdd: 2 failures + 1 success → caller sees the sum, tool ran 3 times")
    void retryEventuallySucceeds() throws Exception {
        // default failuresBeforeSuccess = 2, so the 3rd attempt is the success.
        String json = toolRegistry.executeTool("flakyAdd", "{\"a\":\"4\",\"b\":\"5\"}");

        assertFalse(json.startsWith("{\"error\""),
                "Expected success after retries, got: " + json);
        JsonNode node = MAPPER.readTree(json);
        assertEquals(9, node.get("result").asInt());
        assertEquals(3, node.get("attemptNumber").asInt(),
                "The successful attempt should be the third");
        assertEquals(3, flakyTool.getFlakyAddInvocations(),
                "flakyAdd should have been invoked exactly 3 times");
    }

    @Test
    @DisplayName("flakyAdd: when failures exceed maxAttempts the retry budget is consumed and errorJson is returned")
    void retryBudgetIsCappedAtMaxAttempts() throws Exception {
        // Bump failuresBeforeSuccess past maxAttempts (3) → never succeeds.
        flakyTool.setFailuresBeforeSuccess(5);

        String json = toolRegistry.executeTool("flakyAdd", "{\"a\":\"4\",\"b\":\"5\"}");

        assertTrue(json.startsWith("{\"error\""), "Expected errorJson, got: " + json);
        JsonNode node = MAPPER.readTree(json);
        assertTrue(node.get("error").asText().contains("Tool execution failed"));
        assertEquals(3, flakyTool.getFlakyAddInvocations(),
                "@ToolRetry(maxAttempts=3) must stop after 3 invocations");
    }

    // ── 2. Always fails (retried exception) ─────────────────────────

    @Test
    @DisplayName("alwaysFail: IOException on every attempt → errorJson and invocations == maxAttempts")
    void retryExhaustedReturnsErrorJson() throws Exception {
        String json = toolRegistry.executeTool("alwaysFail",
                "{\"reason\":\"network down\"}");

        assertTrue(json.startsWith("{\"error\""), "Expected errorJson, got: " + json);
        JsonNode node = MAPPER.readTree(json);
        assertTrue(node.get("error").asText().contains("Tool execution failed"));
        assertEquals(3, flakyTool.getAlwaysFailInvocations(),
                "alwaysFail must have been retried up to maxAttempts");
    }

    // ── 3. Exception not in retryOn ─────────────────────────────────

    @Test
    @DisplayName("Exception not in retryOn → no retry, invocation count == 1")
    void nonRetriedExceptionIsNotRetried() {
        String json = toolRegistry.executeTool("failWithNonRetriedException",
                "{\"reason\":\"validation\"}");

        assertTrue(json.startsWith("{\"error\""), "Expected errorJson, got: " + json);
        assertEquals(1, flakyTool.getNonRetriedInvocations(),
                "Exception not in retryOn must NOT trigger any retry");
    }

    // ── 4. abortOn short-circuits retry ─────────────────────────────

    @Test
    @DisplayName("Exception in abortOn short-circuits retries even when retryOn would match")
    void abortOnShortCircuitsRetries() {
        // retryOn = Exception.class would normally retry. abortOn wins.
        String json = toolRegistry.executeTool("failWithAbortException",
                "{\"reason\":\"invariant\"}");

        assertTrue(json.startsWith("{\"error\""), "Expected errorJson, got: " + json);
        assertEquals(1, flakyTool.getAbortOnInvocations(),
                "abortOn must short-circuit before the second invocation");
    }

    // ── Metrics ─────────────────────────────────────────────────────

    @Test
    @DisplayName("Micrometer counter agent.tool.retry.attempts increments on each retry")
    void retryMetricsAreIncremented() {
        double beforeAttempts = counter("agent.tool.retry.attempts", "tool", "flakyAdd");
        double beforeExhausted = counter("agent.tool.retry.exhausted", "tool", "alwaysFail");

        // flakyAdd: 2 retries (3 attempts, 2 of which are retries)
        toolRegistry.executeTool("flakyAdd", "{\"a\":\"1\",\"b\":\"2\"}");
        // alwaysFail: 2 retries + 1 exhaustion signal
        toolRegistry.executeTool("alwaysFail", "{\"reason\":\"x\"}");

        double afterAttempts  = counter("agent.tool.retry.attempts", "tool", "flakyAdd");
        double afterExhausted = counter("agent.tool.retry.exhausted", "tool", "alwaysFail");

        assertEquals(2.0, afterAttempts  - beforeAttempts,  0.0001,
                "flakyAdd should record 2 retry attempts (3 total invocations - initial)");
        assertEquals(1.0, afterExhausted - beforeExhausted, 0.0001,
                "alwaysFail should record 1 exhaustion event");
    }

    // ── helper ─────────────────────────────────────────────────────

    private double counter(String name, String... tags) {
        var c = meterRegistry.find(name).tags(tags).counter();
        return c == null ? 0.0 : c.count();
    }
}
