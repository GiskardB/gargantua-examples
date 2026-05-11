package ai.gargantua.example.toolrbac;

import ai.gargantua.autoconfigure.ToolRegistry;
import ai.gargantua.core.security.SecurityContext;
import ai.gargantua.core.tool.ToolExecutionContext;
import ai.gargantua.example.toolrbac.tools.AdminTool;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Pins every observable branch of {@code @RequiresRole}. The gate lives in
 * {@code ToolRegistry.executeTool}, so the test drives it directly with
 * synthetic {@link ToolExecutionContext} values — no LLM, no Mongo, no
 * Redis, no chat controller in the loop.
 *
 * <p>Branches covered:</p>
 * <ol>
 *   <li>No {@code SecurityContext} + a role-gated tool → fail closed
 *       (deny with {@code Access denied: no security context...}).</li>
 *   <li>{@code SecurityContext} present but the user lacks the required
 *       role → deny with the role list mentioned in the error.</li>
 *   <li>{@code SecurityContext} with the required role → execute.</li>
 *   <li>Multi-role annotation ({@code any-of}): user with the second
 *       listed role is allowed.</li>
 *   <li>{@code super-admin} acts as a wildcard, granting any required
 *       role per {@link SecurityContext#hasRole}.</li>
 *   <li>A tool without {@code @RequiresRole} is invocable even by an
 *       anonymous caller.</li>
 *   <li>Tool body is NEVER run on a denied call — the counter stays
 *       at the value it had before the call.</li>
 * </ol>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@ActiveProfiles("embedded")
class ToolRbacApplicationTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Autowired private ToolRegistry toolRegistry;
    @Autowired private AdminTool    adminTool;

    @BeforeEach
    void resetCounters() {
        adminTool.resetCounters();
    }

    // ── 1. Fail closed without security context ─────────────────────

    @Test
    @DisplayName("No SecurityContext + @RequiresRole → deny with structured error, tool body never runs")
    void denyWithoutSecurityContext() throws Exception {
        String json = toolRegistry.executeTool("viewAuditLog", "{}"); // empty ToolExecutionContext

        JsonNode node = MAPPER.readTree(json);
        assertNotNull(node.get("error"), "expected access-denied JSON, got: " + json);
        assertTrue(node.get("error").asText().contains("no security context"),
                "error payload should explain the missing context: " + json);
        assertEquals(0, adminTool.getViewAuditLogCalls(),
                "tool body must not run when the gate denies");
    }

    // ── 2. Insufficient roles ───────────────────────────────────────

    @Test
    @DisplayName("User without the required role → deny, error mentions the roles")
    void denyWithWrongRole() throws Exception {
        var ctx = ToolExecutionContext.of(
                new SecurityContext("alice", null, Set.of("user")), null);

        String json = toolRegistry.executeTool("viewAuditLog", "{}", ctx);

        JsonNode node = MAPPER.readTree(json);
        assertNotNull(node.get("error"));
        assertTrue(node.get("error").asText().contains("auditor"),
                "error should mention the required role(s): " + json);
        assertEquals(0, adminTool.getViewAuditLogCalls());
    }

    // ── 3. Correct role allows execution ────────────────────────────

    @Test
    @DisplayName("User with the required role is allowed; tool body runs exactly once")
    void allowWithCorrectRole() {
        var ctx = ToolExecutionContext.of(
                new SecurityContext("alice", null, Set.of("auditor")), null);

        String result = toolRegistry.executeTool("viewAuditLog", "{}", ctx);

        assertEquals("audit-entries=42;calls=1", result);
        assertEquals(1, adminTool.getViewAuditLogCalls());
    }

    // ── 4. Multi-role annotation (any-of) ──────────────────────────

    @Test
    @DisplayName("Any-of: user with one of the listed roles is allowed")
    void allowWithAnyOfTheListedRoles() {
        // deleteUser requires {"admin", "owner"} — alice has only "owner".
        var ctx = ToolExecutionContext.of(
                new SecurityContext("alice", null, Set.of("owner")), null);

        String result = toolRegistry.executeTool("deleteUser",
                "{\"userId\":\"bob\"}", ctx);

        assertEquals("deleted=bob;calls=1", result);
        assertEquals(1, adminTool.getDeleteUserCalls());
    }

    @Test
    @DisplayName("Any-of: user with NONE of the listed roles is denied")
    void denyWhenNoneOfTheRolesMatch() throws Exception {
        var ctx = ToolExecutionContext.of(
                new SecurityContext("eve", null, Set.of("guest")), null);

        String json = toolRegistry.executeTool("deleteUser",
                "{\"userId\":\"bob\"}", ctx);

        JsonNode node = MAPPER.readTree(json);
        assertNotNull(node.get("error"));
        assertTrue(node.get("error").asText().contains("admin"));
        assertTrue(node.get("error").asText().contains("owner"));
        assertEquals(0, adminTool.getDeleteUserCalls());
    }

    // ── 5. super-admin wildcard ─────────────────────────────────────

    @Test
    @DisplayName("super-admin acts as a wildcard role and is allowed everywhere")
    void superAdminBypassesEveryGate() {
        var superAdmin = ToolExecutionContext.of(
                new SecurityContext("root", null, Set.of("super-admin")), null);

        String r1 = toolRegistry.executeTool("viewAuditLog", "{}", superAdmin);
        String r2 = toolRegistry.executeTool("deleteUser",
                "{\"userId\":\"bob\"}", superAdmin);

        assertEquals("audit-entries=42;calls=1", r1);
        assertEquals("deleted=bob;calls=1", r2);
    }

    // ── 6. No annotation = no gate ─────────────────────────────────

    @Test
    @DisplayName("Tool without @RequiresRole runs even for an anonymous caller")
    void unannotatedToolRunsAnonymously() {
        // No ToolExecutionContext — i.e. no SecurityContext.
        String result = toolRegistry.executeTool("publicInfo", "{}");
        assertEquals("service=gargantua;calls=1", result);
        assertEquals(1, adminTool.getPublicInfoCalls());
    }

    // ── 7. Mixed-tool sanity ───────────────────────────────────────

    @Test
    @DisplayName("Denied call has no side effect on the counter, allowed call does — proven by interleaving")
    void deniedCallsLeaveCounterUntouched() {
        // 1. Deny: bumps no counter.
        toolRegistry.executeTool("viewAuditLog", "{}"); // no context → deny
        toolRegistry.executeTool("viewAuditLog", "{}",  // wrong role → deny
                ToolExecutionContext.of(new SecurityContext("eve", null, Set.of("user")), null));
        assertEquals(0, adminTool.getViewAuditLogCalls(),
                "two denied calls must leave the counter at zero");

        // 2. Allow: bumps to 1.
        toolRegistry.executeTool("viewAuditLog", "{}",
                ToolExecutionContext.of(new SecurityContext("alice", null, Set.of("auditor")), null));
        assertEquals(1, adminTool.getViewAuditLogCalls());

        // 3. Deny again: stays at 1.
        toolRegistry.executeTool("viewAuditLog", "{}");
        assertEquals(1, adminTool.getViewAuditLogCalls());
    }
}
