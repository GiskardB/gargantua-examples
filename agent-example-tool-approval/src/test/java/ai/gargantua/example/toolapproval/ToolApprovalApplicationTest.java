package ai.gargantua.example.toolapproval;

import ai.gargantua.autoconfigure.ToolRegistry;
import ai.gargantua.core.hitl.ApprovalDecision;
import ai.gargantua.core.hitl.ApprovalRequest;
import ai.gargantua.core.hitl.ApprovalStore;
import ai.gargantua.core.tool.ToolDefinition;
import ai.gargantua.example.toolapproval.tools.MoneyTransferTool;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Pins the observable contract of {@code @RequiresApproval}: tool metadata,
 * {@link ApprovalStore} lifecycle (savePending / getPending / resolve /
 * isExpired), and the REST resolution endpoint
 * {@code POST /api/agent/approval/{requestId}}.
 *
 * <p>What is deliberately NOT exercised here: the streaming chat path's
 * SSE pause behaviour. That requires booting a streaming controller and
 * driving an LLM, which is out of scope for an annotation-contract pin.
 * The README explains the streaming flow textually.</p>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@ActiveProfiles("embedded")
class ToolApprovalApplicationTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Autowired private ToolRegistry      toolRegistry;
    @Autowired private MoneyTransferTool tool;
    @Autowired private ApprovalStore     approvalStore;
    @Autowired private WebApplicationContext webContext;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        tool.resetBalances();
        mockMvc = MockMvcBuilders.webAppContextSetup(webContext).build();
    }

    // ── 1. Tool metadata reaches ToolDefinition ────────────────────

    @Test
    @DisplayName("`transfer` is flagged with requiresApproval, the approval message and dangerous")
    void transferMetadataIsRegistered() {
        ToolDefinition def = definition("transfer");
        assertTrue(def.requiresApproval(), "transfer should be marked requiresApproval");
        assertTrue(def.dangerous(),        "transfer is annotated dangerous=true");
        assertTrue(def.approvalMessage().contains("transfer money"),
                "ToolDefinition should carry the annotation's `message` verbatim: " + def.approvalMessage());
    }

    @Test
    @DisplayName("`getBalance` carries no approval metadata — it is read-only by design")
    void getBalanceCarriesNoApprovalMetadata() {
        ToolDefinition def = definition("getBalance");
        assertFalse(def.requiresApproval());
        assertFalse(def.dangerous());
        assertEquals("", def.approvalMessage());
    }

    // ── 2. ApprovalStore lifecycle ─────────────────────────────────

    @Test
    @DisplayName("savePending then getPending returns the request; resolve removes it")
    void approvalStoreRoundTrip() {
        String requestId = UUID.randomUUID().toString();
        approvalStore.savePending(requestId, samplePending(requestId, +60), Duration.ofMinutes(5));

        Optional<ApprovalRequest> before = approvalStore.getPending(requestId);
        assertTrue(before.isPresent(), "pending request should be retrievable right after savePending");
        assertEquals("transfer", before.get().toolName());

        approvalStore.resolve(requestId, new ApprovalDecision(requestId, "APPROVED", null));
        assertTrue(approvalStore.getPending(requestId).isEmpty(),
                "after resolve, getPending must return empty");
    }

    @Test
    @DisplayName("isExpired returns true for an unknown id and for a request whose expiresAt is in the past")
    void approvalStoreExpiry() {
        String unknown = UUID.randomUUID().toString();
        assertTrue(approvalStore.isExpired(unknown),
                "an unknown id is treated as expired so callers never block on it");

        String stale = UUID.randomUUID().toString();
        approvalStore.savePending(stale, samplePending(stale, -1), Duration.ofMinutes(5));
        assertTrue(approvalStore.isExpired(stale),
                "a request whose expiresAt is in the past must report isExpired=true");
        // Expired entries are lazily evicted on getPending — confirm that.
        assertTrue(approvalStore.getPending(stale).isEmpty());
    }

    // ── 3. REST resolution endpoint ────────────────────────────────

    @Test
    @DisplayName("POST /api/agent/approval/{id} resolves the pending request")
    void restResolvesPendingRequest() throws Exception {
        String requestId = UUID.randomUUID().toString();
        approvalStore.savePending(requestId, samplePending(requestId, +60), Duration.ofMinutes(5));

        String body = MAPPER.writeValueAsString(Map.of(
                "decision", "APPROVED",
                "reason", "looks good"));

        mockMvc.perform(post("/api/agent/approval/{id}", requestId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.requestId").value(requestId))
                .andExpect(jsonPath("$.decision").value("APPROVED"))
                .andExpect(jsonPath("$.status").value("resolved"));

        assertTrue(approvalStore.getPending(requestId).isEmpty(),
                "after a successful POST the pending request must be gone");
    }

    @Test
    @DisplayName("POST /api/agent/approval/{unknown} returns 410 Gone")
    void restRejectsUnknownRequestId() throws Exception {
        String body = MAPPER.writeValueAsString(Map.of("decision", "APPROVED"));
        mockMvc.perform(post("/api/agent/approval/{id}", "no-such-id")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isGone());
    }

    // ── 4. Tool body still runs when called directly (NOT via streaming) ──

    @Test
    @DisplayName("Direct toolRegistry.executeTool('transfer', …) DOES run the tool body — gating lives in the streaming controller, not the registry")
    void registryDoesNotGateApproval() {
        tool.seedBalance("alice", 1000L);
        tool.seedBalance("bob",   0L);

        String result = toolRegistry.executeTool("transfer",
                "{\"from\":\"alice\",\"to\":\"bob\",\"amount\":\"100\"}");

        // Result is JSON — the registry's contract is "run, serialise, return".
        assertFalse(result.startsWith("{\"error\""), "expected success, got: " + result);
        assertEquals(900L, tool.balanceOf("alice"));
        assertEquals(100L, tool.balanceOf("bob"));
    }

    // ── helpers ────────────────────────────────────────────────────

    private ToolDefinition definition(String name) {
        return toolRegistry.getToolDefinitions().stream()
                .filter(d -> d.name().equals(name))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Tool '" + name + "' not registered"));
    }

    private ApprovalRequest samplePending(String requestId, long expiresInSeconds) {
        return new ApprovalRequest(
                requestId,
                "session-1",
                "alice",
                "transfer",
                Map.of("from", "alice", "to", "bob", "amount", "100"),
                "About to transfer money",
                true,
                Instant.now().plusSeconds(expiresInSeconds)
        );
    }
}
