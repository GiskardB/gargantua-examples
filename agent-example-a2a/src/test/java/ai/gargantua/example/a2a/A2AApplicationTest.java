package ai.gargantua.example.a2a;

import ai.gargantua.autoconfigure.AgentCardService;
import ai.gargantua.autoconfigure.HttpA2AClient;
import ai.gargantua.core.a2a.A2ATask;
import ai.gargantua.core.a2a.AgentCard;
import ai.gargantua.core.orchestrator.AgentRequest;
import ai.gargantua.core.orchestrator.AgentResponse;
import ai.gargantua.core.orchestrator.OrchestratorEngine;
import ai.gargantua.core.orchestrator.RoutingMethod;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Pins the A2A protocol surface end-to-end. Boots the example app on a
 * random port with a stubbed {@link OrchestratorEngine} and exercises:
 *
 * <ul>
 *   <li>{@link AgentCardService} construction (server identity, skill
 *       filtering, tags = [domain, …allowedTools], examples passthrough).</li>
 *   <li>The {@code /.well-known/agent.json} discovery endpoint.</li>
 *   <li>The {@code /a2a} JSON-RPC surface (message/send happy path,
 *       {@code skillHint} → {@code forceSkill}, error cases for missing
 *       method / unknown method / missing message / unsupported task ops).</li>
 *   <li>{@link HttpA2AClient#discover} and {@link HttpA2AClient#sendTask}
 *       round-trips against the loop-back HTTP endpoint.</li>
 * </ul>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ContextConfiguration(classes = {A2AApplication.class, A2AApplicationTest.StubEngineConfig.class})
@ActiveProfiles("embedded")
class A2AApplicationTest {

    @LocalServerPort private int port;

    @Autowired private AgentCardService agentCardService;
    @Autowired private OrchestratorEngine orchestratorEngine;     // mocked
    @Autowired private HttpA2AClient httpA2AClient;

    private final RestClient rest = RestClient.create();

    @BeforeEach
    void resetMock() {
        // Spring caches the test context (and its mock) across tests; reset
        // interactions and stubbing between methods so verifications are clean.
        reset(orchestratorEngine);
    }

    private String baseUrl() {
        return "http://localhost:" + port;
    }

    private static AgentResponse stubResponse(String text, String sessionId, String skillUsed) {
        return new AgentResponse(text, sessionId, skillUsed, List.of(),
                RoutingMethod.SEMANTIC, 0.9, 3, 2, 5, 0.0, 1L, false);
    }

    // ─── 1. AgentCardService — direct bean ────────────────────────────

    @Test
    @DisplayName("AgentCardService builds a card with server identity from agent.api.* properties")
    void agentCardCarriesServerIdentity() {
        AgentCard card = agentCardService.getAgentCard("https://example.test");

        assertEquals("Gargantua Demo A2A Agent", card.name());
        assertEquals("Demo agent exposing the A2A protocol surface.", card.description());
        assertEquals("1.4.2", card.version());
        assertEquals("https://example.test", card.url());
        assertEquals("1.0", card.protocolVersion());
        assertNotNull(card.capabilities());
        assertEquals(List.of("text/plain"), card.defaultInputModes());
        assertEquals(List.of("text/plain"), card.defaultOutputModes());
        assertEquals(1, card.authSchemes().size());
        assertEquals("none", card.authSchemes().get(0).scheme());
    }

    @Test
    @DisplayName("AgentCardService filters out skills with metadata.active=false")
    void agentCardOmitsInactiveSkills() {
        AgentCard card = agentCardService.getAgentCard("http://localhost");

        assertTrue(card.skills().stream().anyMatch(s -> "billing-skill".equals(s.id())),
                "billing-skill (active) must appear");
        assertFalse(card.skills().stream().anyMatch(s -> "inactive-skill".equals(s.id())),
                "inactive-skill (metadata.active=false) must be filtered out");
    }

    @Test
    @DisplayName("Skill tags = [domain, …allowedTools] and examples are propagated from SKILL.md")
    void agentCardSkillTagsAndExamples() {
        AgentCard card = agentCardService.getAgentCard("http://localhost");
        AgentCard.AgentSkill billing = card.skills().stream()
                .filter(s -> "billing-skill".equals(s.id()))
                .findFirst()
                .orElseThrow();

        assertEquals("billing-skill", billing.name());
        assertEquals("billing", billing.domain());
        assertTrue(billing.tags().contains("billing"), "tags must start with the domain");
        assertTrue(billing.tags().contains("lookupInvoice"),
                "tags must also include the allowed-tools entries; got: " + billing.tags());
        assertEquals(2, billing.examples().size());
        assertTrue(billing.examples().get(0).contains("invoice 12345"));
    }

    // ─── 2. /.well-known/agent.json (REST) ────────────────────────────

    @Test
    @DisplayName("GET /.well-known/agent.json returns 200 with the AgentCard JSON")
    void wellKnownAgentJsonExposesCard() {
        AgentCard fetched = rest.get()
                .uri(baseUrl() + "/.well-known/agent.json")
                .retrieve()
                .body(AgentCard.class);

        assertNotNull(fetched);
        assertEquals("Gargantua Demo A2A Agent", fetched.name());
        assertEquals("1.4.2", fetched.version());
        // baseUrl derived from request — should be loopback on the random port
        assertTrue(fetched.url().startsWith("http://"), "url must echo the request scheme/host");
    }

    // ─── 3. /a2a JSON-RPC — happy path ────────────────────────────────

    @Test
    @DisplayName("POST /a2a with method=message/send and message.parts → completed task with artifact")
    @SuppressWarnings("unchecked")
    void a2aMessageSendCompletesTask() {
        when(orchestratorEngine.invoke(any(AgentRequest.class)))
                .thenReturn(stubResponse("invoice 12345 is PAID", "ignored", "billing-skill"));

        Map<String, Object> response = sendJsonRpc("message/send", Map.of(
                "message", Map.of("parts", List.of(Map.of("kind", "text", "text", "what is invoice 12345?"))),
                "contextId", "ctx-1"
        ), 100);

        assertEquals("2.0", response.get("jsonrpc"));
        assertEquals(100, response.get("id"));
        Map<String, Object> result = (Map<String, Object>) response.get("result");
        assertNotNull(result, "happy path must populate result, not error");
        assertEquals("task", result.get("kind"));
        assertEquals("ctx-1", result.get("contextId"));

        Map<String, Object> status = (Map<String, Object>) result.get("status");
        assertEquals("completed", status.get("state"));

        List<Map<String, Object>> artifacts = (List<Map<String, Object>>) result.get("artifacts");
        assertEquals(1, artifacts.size());
        Map<String, Object> artifact = artifacts.get(0);
        assertEquals("response", artifact.get("name"));
        List<Map<String, Object>> parts = (List<Map<String, Object>>) artifact.get("parts");
        assertEquals("text", parts.get(0).get("kind"));
        assertEquals("invoice 12345 is PAID", parts.get(0).get("text"));

        Map<String, Object> metadata = (Map<String, Object>) result.get("metadata");
        assertEquals("billing-skill", metadata.get("skillUsed"));
    }

    @Test
    @DisplayName("POST /a2a with skillHint forces the skill on the AgentRequest passed to the orchestrator")
    void a2aSkillHintBecomesForceSkill() {
        when(orchestratorEngine.invoke(any(AgentRequest.class)))
                .thenReturn(stubResponse("ok", "ignored", "billing-skill"));

        sendJsonRpc("message/send", Map.of(
                "message", Map.of("parts", List.of(Map.of("kind", "text", "text", "ping"))),
                "skillHint", "billing-skill"
        ), 101);

        ArgumentCaptor<AgentRequest> captor = ArgumentCaptor.forClass(AgentRequest.class);
        verify(orchestratorEngine).invoke(captor.capture());
        AgentRequest forwarded = captor.getValue();
        assertEquals("billing-skill", forwarded.forceSkill(),
                "skillHint must propagate as AgentRequest.forceSkill so routing is bypassed");
        assertEquals("a2a-agent", forwarded.userId(),
                "/a2a uses 'a2a-agent' as the user identity");
    }

    @Test
    @DisplayName("POST /a2a with engine exception → status=failed task (still a result, never an error)")
    @SuppressWarnings("unchecked")
    void a2aEngineExceptionYieldsFailedTask() {
        when(orchestratorEngine.invoke(any(AgentRequest.class)))
                .thenThrow(new RuntimeException("engine boom"));

        Map<String, Object> response = sendJsonRpc("message/send", Map.of(
                "message", Map.of("parts", List.of(Map.of("kind", "text", "text", "boom?")))
        ), 102);

        Map<String, Object> result = (Map<String, Object>) response.get("result");
        assertNotNull(result, "exceptions must still come back as result/failed, never JSON-RPC error");
        Map<String, Object> status = (Map<String, Object>) result.get("status");
        assertEquals("failed", status.get("state"));
        Map<String, Object> metadata = (Map<String, Object>) result.get("metadata");
        assertEquals("engine boom", metadata.get("error"));
    }

    // ─── 4. /a2a JSON-RPC — error paths ───────────────────────────────

    @Test
    @DisplayName("POST /a2a without method → -32600 Invalid Request")
    @SuppressWarnings("unchecked")
    void a2aMissingMethodReturnsInvalidRequest() {
        Map<String, Object> response = postJsonRpcRaw(Map.of(
                "jsonrpc", "2.0",
                "id", 200,
                "params", Map.of()));

        Map<String, Object> error = (Map<String, Object>) response.get("error");
        assertEquals(-32600, error.get("code"));
        assertTrue(((String) error.get("message")).startsWith("Invalid Request"));
    }

    @Test
    @DisplayName("POST /a2a with unknown method → -32601 Method not found")
    @SuppressWarnings("unchecked")
    void a2aUnknownMethodReturnsMethodNotFound() {
        Map<String, Object> response = sendJsonRpc("nope/notReal", Map.of(), 201);
        Map<String, Object> error = (Map<String, Object>) response.get("error");
        assertEquals(-32601, error.get("code"));
        assertTrue(((String) error.get("message")).contains("nope/notReal"));
    }

    @Test
    @DisplayName("POST /a2a message/send without message.parts → -32602 Invalid params")
    @SuppressWarnings("unchecked")
    void a2aMissingMessagePartsReturnsInvalidParams() {
        Map<String, Object> response = sendJsonRpc("message/send", Map.of(), 202);
        Map<String, Object> error = (Map<String, Object>) response.get("error");
        assertEquals(-32602, error.get("code"));
    }

    @Test
    @DisplayName("POST /a2a tasks/get and tasks/cancel return -32602 (task storage not implemented)")
    @SuppressWarnings("unchecked")
    void a2aTaskGetAndCancelAreNotImplementedSynchronously() {
        Map<String, Object> get = sendJsonRpc("tasks/get",
                Map.of("taskId", UUID.randomUUID().toString()), 203);
        Map<String, Object> getError = (Map<String, Object>) get.get("error");
        assertEquals(-32602, getError.get("code"));
        assertTrue(((String) getError.get("message")).toLowerCase().contains("task"));

        Map<String, Object> cancel = sendJsonRpc("tasks/cancel",
                Map.of("taskId", UUID.randomUUID().toString()), 204);
        Map<String, Object> cancelError = (Map<String, Object>) cancel.get("error");
        assertEquals(-32602, cancelError.get("code"));
    }

    // ─── 5. HttpA2AClient — loop-back round-trips ─────────────────────

    @Test
    @DisplayName("HttpA2AClient.discover round-trips /.well-known/agent.json into an AgentCard")
    void httpClientDiscoverRoundTrip() {
        AgentCard card = httpA2AClient.discover(baseUrl());
        assertEquals("Gargantua Demo A2A Agent", card.name());
        assertTrue(card.skills().stream().anyMatch(s -> "billing-skill".equals(s.id())));
    }

    @Test
    @DisplayName("HttpA2AClient.sendTask round-trips a completed task with the agent's response text")
    void httpClientSendTaskRoundTrip() {
        when(orchestratorEngine.invoke(any(AgentRequest.class)))
                .thenReturn(stubResponse("paid invoice 12345", "ignored", "billing-skill"));

        A2ATask task = httpA2AClient.sendTask(baseUrl(), "what is invoice 12345?", null);
        assertNotNull(task);
        assertEquals("task", task.kind());
        assertEquals("completed", task.status().state());
        assertEquals("paid invoice 12345", task.artifacts().get(0).parts().get(0).text());
        assertEquals("billing-skill", task.metadata().get("skillUsed"));
    }

    @Test
    @DisplayName("HttpA2AClient.getTask returns Optional.empty when the server reports a task error")
    void httpClientGetTaskHandlesUnknownId() {
        // The server replies with a JSON-RPC error for tasks/get (storage not
        // implemented). The client must surface this as Optional.empty().
        var maybe = httpA2AClient.getTask(baseUrl(), UUID.randomUUID().toString());
        assertTrue(maybe.isEmpty(), "Server-side task error must collapse to Optional.empty");
    }

    // ─── 6. Test plumbing ─────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    private Map<String, Object> sendJsonRpc(String method, Map<String, Object> params, int id) {
        Map<String, Object> body = new java.util.LinkedHashMap<>();
        body.put("jsonrpc", "2.0");
        body.put("method", method);
        body.put("params", params);
        body.put("id", id);
        return postJsonRpcRaw(body);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private Map<String, Object> postJsonRpcRaw(Map<String, Object> body) {
        return rest.post()
                .uri(baseUrl() + "/a2a")
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                .accept(org.springframework.http.MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .body(Map.class);
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
