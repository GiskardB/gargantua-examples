package ai.gargantua.example.toolrbac.tools;

import ai.gargantua.core.security.RequiresRole;
import ai.gargantua.core.tool.AgentTool;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * Toy admin tool used to exercise every branch of {@link RequiresRole}.
 *
 * <p>Three methods on purpose:</p>
 * <ul>
 *   <li>{@link #viewAuditLog()} — gated by a single role.</li>
 *   <li>{@link #deleteUser(String)} — gated by an array of roles
 *       (any-of semantics).</li>
 *   <li>{@link #publicInfo()} — no {@code @RequiresRole}; control case
 *       that proves the gate only fires when the annotation is present.</li>
 * </ul>
 *
 * <p>Each method increments a per-method counter on every actual
 * invocation. Combined with the {@code @RequiresRole} gate, the counter
 * lets tests prove that a denied call never reaches the body.</p>
 */
@Component
public class AdminTool {

    private final AtomicInteger viewAuditLogCalls = new AtomicInteger();
    private final AtomicInteger deleteUserCalls   = new AtomicInteger();
    private final AtomicInteger publicInfoCalls   = new AtomicInteger();

    public int getViewAuditLogCalls() { return viewAuditLogCalls.get(); }
    public int getDeleteUserCalls()   { return deleteUserCalls.get(); }
    public int getPublicInfoCalls()   { return publicInfoCalls.get(); }

    public void resetCounters() {
        viewAuditLogCalls.set(0);
        deleteUserCalls.set(0);
        publicInfoCalls.set(0);
    }

    // ── 1. Single role ─────────────────────────────────────────────

    @AgentTool(description = """
            Returns the audit log. Restricted to users with the `auditor` role.
            """)
    @RequiresRole("auditor")
    public String viewAuditLog() {
        int n = viewAuditLogCalls.incrementAndGet();
        return "audit-entries=42;calls=" + n;
    }

    // ── 2. Multiple roles (any-of) ─────────────────────────────────

    @AgentTool(description = """
            Deletes a user. Restricted to users with either `admin` or `owner`
            role — having either one is sufficient.
            """)
    @RequiresRole({"admin", "owner"})
    public String deleteUser(String userId) {
        int n = deleteUserCalls.incrementAndGet();
        return "deleted=" + userId + ";calls=" + n;
    }

    // ── 3. No @RequiresRole — control ──────────────────────────────

    @AgentTool(description = """
            Returns harmless public information. No role required — any caller
            (including unauthenticated ones) can invoke this.
            """)
    public String publicInfo() {
        int n = publicInfoCalls.incrementAndGet();
        return "service=gargantua;calls=" + n;
    }
}
