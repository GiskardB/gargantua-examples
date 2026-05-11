package ai.gargantua.example.toolbasics;

import ai.gargantua.autoconfigure.ToolRegistry;
import ai.gargantua.core.tool.ToolDefinition;
import ai.gargantua.example.toolbasics.tools.CalculatorTool;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.ActiveProfiles;

import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifies the full {@code @AgentTool} contract end-to-end without booting an
 * LLM, MongoDB or Redis. Each test pins a specific guarantee that
 * {@link ai.gargantua.core.tool.AgentTool} makes:
 *
 * <ol>
 *   <li>Spring context boots in embedded mode → tool registration is part of
 *       the standard boot path.</li>
 *   <li>The {@code CalculatorTool} bean is discovered by classpath scanning.</li>
 *   <li>{@link ToolRegistry} contains exactly the three tools we expect, by
 *       the names the LLM sees — including the {@code power} → {@code pow}
 *       override.</li>
 *   <li>The descriptions and {@code parallelizable} flags survive into
 *       {@link ToolDefinition} unchanged.</li>
 *   <li>{@code ToolRegistry.executeTool(name, jsonArgs)} — the exact path the
 *       orchestrator drives when an LLM emits a tool call — runs reflectively,
 *       converts JSON strings to primitives, invokes the method, and
 *       JSON-serialises the return value.</li>
 *   <li>Unknown tools and runtime exceptions are returned as structured
 *       {@code {"error": "..."}} payloads rather than thrown.</li>
 * </ol>
 *
 * <p>Run with {@code mvn test}. No Docker, no API keys, no Internet.</p>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@ActiveProfiles("embedded")
class ToolBasicsApplicationTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Autowired private ApplicationContext ctx;
    @Autowired private ToolRegistry toolRegistry;
    @Autowired private CalculatorTool calculatorTool;

    // ── 1. Wiring ────────────────────────────────────────────────────

    @Test
    @DisplayName("Spring context boots in embedded profile")
    void contextLoads() {
        assertNotNull(ctx);
    }

    @Test
    @DisplayName("CalculatorTool is registered as a Spring bean")
    void calculatorToolBeanIsRegistered() {
        assertNotNull(calculatorTool);
        assertSame(calculatorTool, ctx.getBean(CalculatorTool.class));
    }

    // ── 2. @AgentTool discovery & metadata ──────────────────────────

    @Test
    @DisplayName("ToolRegistry exposes exactly the three @AgentTool methods, by the names the LLM sees")
    void allThreeToolsAreRegisteredByLlmName() {
        var names = toolRegistry.getAllToolNames();

        assertTrue(names.contains("add"),      "add should be registered (method name)");
        assertTrue(names.contains("multiply"), "multiply should be registered (method name)");
        assertTrue(names.contains("pow"),      "pow should be registered (overrides Java name 'power')");

        // The Java method 'power' must NOT leak as a tool name — the @AgentTool(name="pow")
        // override is the LLM-facing identifier.
        assertFalse(names.contains("power"),
                "Java method name 'power' must not be exposed when @AgentTool name=\"pow\" is set");
    }

    @Test
    @DisplayName("ToolDefinition.description is the value declared on the annotation")
    void descriptionsAreWiredThrough() {
        assertTrue(definition("add").description().contains("Adds two integers"));
        assertTrue(definition("multiply").description().contains("structured result"));
        assertTrue(definition("pow").description().contains("exponent"));
    }

    @Test
    @DisplayName("ToolDefinition.parallelizable mirrors @AgentTool(parallelizable=…)")
    void parallelizableFlagIsWiredThrough() {
        assertTrue(definition("add").parallelizable(),      "default is true");
        assertTrue(definition("multiply").parallelizable(), "default is true");
        assertFalse(definition("pow").parallelizable(),
                "pow explicitly sets parallelizable=false");
    }

    // ── 3. Execution path (the contract seen by the orchestrator) ───

    @Test
    @DisplayName("executeTool(add) — primitive return is JSON-encoded as a bare number")
    void executeAddReturnsPrimitiveJson() {
        String result = toolRegistry.executeTool("add", "{\"a\":\"2\",\"b\":\"3\"}");
        assertEquals("5", result);
    }

    @Test
    @DisplayName("executeTool(multiply) — record return is JSON-encoded with field names")
    void executeMultiplyReturnsStructuredJson() throws Exception {
        String json = toolRegistry.executeTool("multiply", "{\"a\":\"3\",\"b\":\"4\"}");

        JsonNode node = MAPPER.readTree(json);
        assertEquals(3,  node.get("a").asInt());
        assertEquals(4,  node.get("b").asInt());
        assertEquals(12, node.get("result").asInt());
    }

    @Test
    @DisplayName("executeTool(pow) — name override is what the orchestrator dispatches on")
    void executePowUsesAnnotationNameNotMethodName() {
        // The orchestrator hands ToolRegistry whatever the LLM emitted. The LLM
        // saw "pow" in the tool spec, so it emits "pow" — not "power".
        String result = toolRegistry.executeTool("pow", "{\"base\":\"2\",\"exponent\":\"10\"}");
        assertEquals("1024", result);

        // And the Java method name is rejected, proving the rename is real.
        String missed = toolRegistry.executeTool("power", "{\"base\":\"2\",\"exponent\":\"10\"}");
        assertTrue(missed.startsWith("{\"error\""),
                "method name 'power' must not resolve a tool: " + missed);
    }

    // ── 4. Error paths return structured JSON, not exceptions ───────

    @Test
    @DisplayName("Unknown tool name → {\"error\":\"Tool not found: …\"}")
    void unknownToolReturnsErrorJson() throws Exception {
        String json = toolRegistry.executeTool("subtract", "{\"a\":\"5\",\"b\":\"2\"}");
        JsonNode node = MAPPER.readTree(json);
        assertTrue(node.get("error").asText().contains("subtract"));
    }

    @Test
    @DisplayName("Runtime exception thrown by the tool body is surfaced as structured JSON (v1.2.3+)")
    void runtimeExceptionIsCaughtAndReturned() throws Exception {
        // pow rejects negative exponents with IllegalArgumentException. As of
        // framework v1.2.3 the registry wraps these into a {"error":"..."}
        // payload regardless of @ToolRetry presence, so the LLM gets a
        // recoverable signal instead of the turn aborting.
        String json = toolRegistry.executeTool("pow", "{\"base\":\"2\",\"exponent\":\"-1\"}");
        JsonNode node = MAPPER.readTree(json);
        assertNotNull(node.get("error"), "expected error field, got: " + json);
        assertTrue(node.get("error").asText().contains("exponent"),
                "error payload should mention the validation reason: " + json);
    }

    // ── 5. Direct invocation (sanity — independent of the registry) ─

    @Test
    @DisplayName("Direct method calls work — useful when unit-testing the tool in isolation")
    void directInvocationStillWorks() {
        assertEquals(7,    calculatorTool.add(3, 4));
        assertEquals(20L,  calculatorTool.multiply(4, 5).result());
        assertEquals(81L,  calculatorTool.power(3, 4));
    }

    // ── helpers ─────────────────────────────────────────────────────

    private ToolDefinition definition(String name) {
        Map<String, ToolDefinition> byName = toolRegistry.getToolDefinitions().stream()
                .collect(java.util.stream.Collectors.toMap(ToolDefinition::name, t -> t));
        return Optional.ofNullable(byName.get(name))
                .orElseThrow(() -> new AssertionError(
                        "Tool '" + name + "' not in registry. Found: " + byName.keySet()));
    }
}
