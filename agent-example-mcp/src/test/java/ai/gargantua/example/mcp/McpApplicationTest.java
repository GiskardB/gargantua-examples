package ai.gargantua.example.mcp;

import ai.gargantua.core.orchestrator.AgentRequest;
import ai.gargantua.core.orchestrator.AgentResponse;
import ai.gargantua.core.orchestrator.OrchestratorEngine;
import ai.gargantua.core.orchestrator.RoutingMethod;
import ai.gargantua.mcp.AgentMcpProperties;
import ai.gargantua.mcp.gateway.ChatMcpTool;
import ai.gargantua.mcp.resources.CapabilitiesMcpResource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.ContextConfiguration;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Pins the MCP server beans wired by {@code AgentMcpServerAutoConfiguration}.
 * Tests boot the embedded profile with a stub {@link OrchestratorEngine} so
 * the gateway tool can be exercised without a real LLM.
 *
 * <p>Covers:</p>
 * <ul>
 *   <li>Auto-config registers both beans when {@code agent.mcp.enabled=true}.</li>
 *   <li>{@code AgentMcpProperties} round-trip from {@code application.yml}.</li>
 *   <li>{@link ChatMcpTool} forwarding (AgentRequest shape, context attributes,
 *       fresh-session helper, JSON-error sentinels for blank/null/exception).</li>
 *   <li>{@link CapabilitiesMcpResource} structure (server identity, gateway
 *       tool entry, live skill listing, live agent-tool listing).</li>
 * </ul>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@ContextConfiguration(classes = {McpApplication.class, McpApplicationTest.StubEngineConfig.class})
@ActiveProfiles("embedded")
class McpApplicationTest {

    @Autowired private AgentMcpProperties properties;
    @Autowired private ChatMcpTool chatMcpTool;
    @Autowired private CapabilitiesMcpResource capabilitiesResource;
    @Autowired private OrchestratorEngine orchestratorEngine; // mock — see StubEngineConfig

    @BeforeEach
    void resetMock() {
        // The Spring context (and its mock OrchestratorEngine) is cached across
        // tests; reset interactions between tests to keep verifications clean.
        reset(orchestratorEngine);
    }

    // ─── 1. Auto-config registration ──────────────────────────────────

    @Test
    @DisplayName("Both MCP beans are registered when agent.mcp.enabled=true")
    void beansAreRegistered() {
        assertNotNull(chatMcpTool);
        assertNotNull(capabilitiesResource);
        assertNotNull(properties);
    }

    // ─── 2. Properties round-trip ─────────────────────────────────────

    @Test
    @DisplayName("application.yml values bind into AgentMcpProperties verbatim")
    void propertiesRoundTrip() {
        assertTrue(properties.isEnabled());
        assertEquals("standalone", properties.getMode());
        assertEquals("gargantua-demo-mcp", properties.getServer().getName());
        assertEquals("1.0.0", properties.getServer().getVersion());
        assertEquals("Demo Gargantua agent exposed over MCP.",
                properties.getServer().getDescription());
        assertEquals("sse", properties.getTransport().getType());
        assertEquals("/mcp", properties.getTransport().getPath());
        assertEquals("ask-the-agent", properties.getGateway().getToolName());
        assertEquals("Send a natural-language message to the Gargantua agent.",
                properties.getGateway().getToolDescription());
        assertFalse(properties.getSecurity().isAuthRequired());
        assertEquals("Authorization", properties.getSecurity().getTokenHeader());
    }

    // ─── 3. ChatMcpTool forwarding ────────────────────────────────────

    @Test
    @DisplayName("chat(message, userId, sessionId) forwards an AgentRequest with source=mcp + mcp.tool attributes")
    void chatForwardsRequestWithMcpAttributes() {
        when(orchestratorEngine.invoke(any(AgentRequest.class)))
                .thenReturn(stubResponse("hello from agent", "session-A"));

        String reply = chatMcpTool.chat("ciao", "claude-desktop", "session-A");
        assertEquals("hello from agent", reply);

        var captor = ArgumentCaptor.forClass(AgentRequest.class);
        verify(orchestratorEngine).invoke(captor.capture());
        AgentRequest forwarded = captor.getValue();
        assertEquals("ciao", forwarded.message());
        assertEquals("claude-desktop", forwarded.userId());
        assertEquals("session-A", forwarded.sessionId());
        assertEquals("mcp", forwarded.contextAttributes().get("source"));
        assertEquals("ask-the-agent", forwarded.contextAttributes().get("mcp.tool"),
                "mcp.tool attribute must echo the configured gateway tool-name");
    }

    @Test
    @DisplayName("chat(message) one-arg overload generates a fresh UUID sessionId and uses mcp-client as userId")
    void chatOneArgOverloadGeneratesSession() {
        when(orchestratorEngine.invoke(any(AgentRequest.class)))
                .thenReturn(stubResponse("ok", "ignored"));

        chatMcpTool.chat("hello");

        var captor = ArgumentCaptor.forClass(AgentRequest.class);
        verify(orchestratorEngine).invoke(captor.capture());
        AgentRequest forwarded = captor.getValue();
        assertEquals("mcp-client", forwarded.userId());
        assertNotNull(forwarded.sessionId());
        assertFalse(forwarded.sessionId().isBlank());
    }

    @Test
    @DisplayName("chat(blank) and chat(null) return the empty-message JSON sentinel without invoking the engine")
    void chatRejectsBlankAndNull() {
        assertEquals("{\"error\":\"empty user message\"}", chatMcpTool.chat("  ", "u", "s"));
        assertEquals("{\"error\":\"empty user message\"}", chatMcpTool.chat(null, "u", "s"));
        verifyNoInteractions(orchestratorEngine);
    }

    @Test
    @DisplayName("chat returns an escaped JSON error sentinel when the engine throws")
    void chatHandlesEngineException() {
        when(orchestratorEngine.invoke(any(AgentRequest.class)))
                .thenThrow(new RuntimeException("boom \"with quote\""));

        String reply = chatMcpTool.chat("anything", "u", "s");
        assertTrue(reply.startsWith("{\"error\":\"agent invocation failed:"),
                "Must wrap engine exceptions as a JSON error sentinel; got: " + reply);
        assertTrue(reply.contains("\\\"with quote\\\""),
                "Double quotes inside the exception message must be escaped");
    }

    // ─── 4. CapabilitiesMcpResource structure ─────────────────────────

    @Test
    @DisplayName("getCapabilities returns the server identity + gateway tool entry from configuration")
    void capabilitiesIncludeServerIdentity() {
        Map<String, Object> caps = capabilitiesResource.getCapabilities();

        assertEquals("gargantua-demo-mcp", caps.get("name"));
        assertEquals("1.0.0", caps.get("version"));
        assertEquals("Demo Gargantua agent exposed over MCP.", caps.get("description"));
        assertEquals("standalone", caps.get("mode"));

        @SuppressWarnings("unchecked")
        Map<String, Object> tools = (Map<String, Object>) caps.get("tools");
        assertEquals(1, tools.size());
        assertEquals("Send a natural-language message to the Gargantua agent.",
                tools.get("ask-the-agent"));
    }

    @Test
    @DisplayName("getCapabilities includes the loaded skills with name + description + version + active flag")
    void capabilitiesIncludeLiveSkills() {
        Map<String, Object> caps = capabilitiesResource.getCapabilities();

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> skills = (List<Map<String, Object>>) caps.get("skills");
        assertFalse(skills.isEmpty(), "default-skill from SKILL.md should appear here");

        Map<String, Object> defaultSkill = skills.stream()
                .filter(s -> "default-skill".equals(s.get("name")))
                .findFirst()
                .orElseThrow();
        assertEquals("1.0.0", defaultSkill.get("version"));
        assertEquals("general", defaultSkill.get("domain"));
        assertEquals(true, defaultSkill.get("active"));
        assertNotNull(defaultSkill.get("description"));
    }

    @Test
    @DisplayName("getCapabilities includes registered agent tools (name + description + flags)")
    void capabilitiesIncludeLiveAgentTools() {
        Map<String, Object> caps = capabilitiesResource.getCapabilities();

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> agentTools = (List<Map<String, Object>>) caps.get("agentTools");
        Map<String, Object> getWeather = agentTools.stream()
                .filter(t -> "getWeather".equals(t.get("name")))
                .findFirst()
                .orElseThrow(() -> new AssertionError(
                        "getWeather @AgentTool from WeatherTool should appear; got: " + agentTools));

        assertEquals("Return a stubbed weather report for a given city.", getWeather.get("description"));
        // Defaults: not approval-gated, not dangerous, parallelizable (the
        // @AgentTool default is `parallelizable = true`; toggle to false on
        // tools with shared mutable state).
        assertEquals(false, getWeather.get("requiresApproval"));
        assertEquals(false, getWeather.get("dangerous"));
        assertEquals(true, getWeather.get("parallelizable"));
    }

    // ─── 5. Stub OrchestratorEngine config ────────────────────────────

    static AgentResponse stubResponse(String text, String sessionId) {
        return new AgentResponse(
                text, sessionId, "default-skill",
                List.of(), RoutingMethod.SEMANTIC, 0.9,
                3, 2, 5, 0.0, 1L, false);
    }

    @org.springframework.boot.test.context.TestConfiguration
    static class StubEngineConfig {
        @Bean
        @Primary
        OrchestratorEngine stubOrchestratorEngine() {
            return mock(OrchestratorEngine.class);
        }
    }
}
