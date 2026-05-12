package ai.gargantua.example.costandaudit;

import ai.gargantua.autoconfigure.AgentProperties;
import ai.gargantua.autoconfigure.AuditService;
import ai.gargantua.autoconfigure.CostTracker;
import ai.gargantua.core.audit.AuditEvent;
import ai.gargantua.core.audit.AuditStore;
import ai.gargantua.core.guardrail.GuardrailResult;
import ai.gargantua.core.orchestrator.AgentRequest;
import ai.gargantua.core.orchestrator.AgentResponse;
import ai.gargantua.core.orchestrator.RoutingMethod;
import ai.gargantua.core.orchestrator.RoutingResult;
import ai.gargantua.core.security.SecurityContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Pins both the cost-tracking and the audit pipeline end-to-end against
 * the in-memory adapters wired by {@code EmbeddedProfileAutoConfiguration}.
 *
 * <ul>
 *   <li>{@link CostTracker} — pricing lookup (nested, colon, and model-only
 *       forms), USD estimation arithmetic, disabled-short-circuit.</li>
 *   <li>{@link AuditStore} (in-memory variant) — record / find-by-id /
 *       find-by-user / find-by-tenant / find-by-session / count semantics.</li>
 *   <li>{@link AuditService#recordRequest} — request→event mapping,
 *       guardrail-event round-trip, audit-disabled short-circuit.</li>
 * </ul>
 *
 * No LLM is invoked.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@ActiveProfiles("embedded")
class CostAndAuditApplicationTest {

    @Autowired private CostTracker costTracker;
    @Autowired private AuditStore  auditStore;
    @Autowired private AuditService auditService;
    @Autowired private AgentProperties properties;

    // ─── helpers ───────────────────────────────────────────────────────

    private AuditEvent stubEvent(String userId, String tenantId, String sessionId, Instant when) {
        return new AuditEvent(
                UUID.randomUUID().toString(),
                when,
                userId, tenantId, sessionId,
                "user message", "agent response",
                "default-skill", "SEMANTIC", 0.92,
                List.of(),                  // toolsCalled
                List.of(),                  // guardrailEvents
                100, 50, 0.0125, 250L,
                false,
                Map.of()
        );
    }

    // ─── 1. CostTracker pricing lookup ────────────────────────────────

    @Test
    @DisplayName("Nested-form pricing (provider.model.suffix) resolves to a non-zero estimate")
    void costEnabledAndPricingResolvesNestedForm() {
        double usd = costTracker.estimateUsd("openai", "gpt-4o", 1000, 1000);
        // 1k input × 0.005 + 1k output × 0.015 = 0.005 + 0.015 = 0.020
        assertEquals(0.020, usd, 1e-9);
    }

    @Test
    @DisplayName("Colon-form pricing (provider:model:suffix) resolves to a non-zero estimate")
    void costEnabledAndPricingResolvesColonForm() {
        double usd = costTracker.estimateUsd("anthropic", "claude-sonnet-4-20250514", 1000, 1000);
        // 0.003 + 0.015 = 0.018
        assertEquals(0.018, usd, 1e-9);
    }

    @Test
    @DisplayName("Model-only fallback resolves when no provider-prefixed key exists")
    void costEnabledModelOnlyFallback() {
        // provider="unknown" — no provider-prefixed key matches; lookup falls back to model-only.
        double usd = costTracker.estimateUsd("unknown", "gpt-4o-mini", 2000, 1000);
        // (2000/1000) × 0.00015 + (1000/1000) × 0.00060 = 0.00030 + 0.00060 = 0.00090
        assertEquals(0.00090, usd, 1e-9);
    }

    @Test
    @DisplayName("USD arithmetic: half a thousand tokens halves the per-1k cost")
    void exactUsdArithmetic() {
        double usd = costTracker.estimateUsd("openai", "gpt-4o", 500, 500);
        // 0.5 × 0.005 + 0.5 × 0.015 = 0.0025 + 0.0075 = 0.010
        assertEquals(0.010, usd, 1e-9);
    }

    @Test
    @DisplayName("Unknown provider+model with no fallback entry → estimate is 0.0 (silent)")
    void unknownModelEstimateIsZero() {
        double usd = costTracker.estimateUsd("brand-new", "not-priced-yet", 1000, 1000);
        assertEquals(0.0, usd, 1e-9);
    }

    @Test
    @DisplayName("Disabled cost-tracking short-circuits estimate to 0.0 regardless of pricing")
    void disabledTrackingShortCircuitsToZero() {
        var props = new AgentProperties();
        props.getCostTracking().setEnabled(false);
        // Even if pricing exists, disabled tracking must return 0.0.
        props.getCostTracking().getPricing().put("openai.gpt-4o.input-per-1k-tokens", 9.99);
        var disabledTracker = new CostTracker(props);

        double usd = disabledTracker.estimateUsd("openai", "gpt-4o", 1_000_000, 1_000_000);
        assertEquals(0.0, usd, 1e-9);
    }

    // ─── 2. AuditStore CRUD (in-memory variant) ───────────────────────

    @Test
    @DisplayName("Audit store record + findById round-trip preserves every field")
    void auditStoreRecordAndFindById() {
        var event = stubEvent("alice", "acme", "s-1", Instant.now());
        auditStore.record(event);

        var fetched = auditStore.findById(event.eventId());
        assertTrue(fetched.isPresent());
        assertEquals(event, fetched.get(), "Records must round-trip by value");
    }

    @Test
    @DisplayName("findByUser respects time-range filtering (older events excluded)")
    void findByUserRespectsTimeRange() {
        String user = "user-range-" + UUID.randomUUID();
        Instant now = Instant.now();
        auditStore.record(stubEvent(user, null, "s", now.minus(2, ChronoUnit.HOURS)));
        auditStore.record(stubEvent(user, null, "s", now.minus(30, ChronoUnit.MINUTES)));
        auditStore.record(stubEvent(user, null, "s", now.minus(5, ChronoUnit.SECONDS)));

        // Window covers only the last hour → expect 2 events (30 min + 5 s ago).
        var hits = auditStore.findByUser(user, now.minus(1, ChronoUnit.HOURS), now.plusSeconds(1), 100);
        assertEquals(2, hits.size());
    }

    @Test
    @DisplayName("findByUser respects the limit parameter")
    void findByUserRespectsLimit() {
        String user = "user-limit-" + UUID.randomUUID();
        Instant base = Instant.now();
        for (int i = 0; i < 5; i++) {
            auditStore.record(stubEvent(user, null, "s", base.minusSeconds(i)));
        }
        var hits = auditStore.findByUser(user, base.minusSeconds(10), base.plusSeconds(1), 3);
        assertEquals(3, hits.size());
    }

    @Test
    @DisplayName("findByTenant filters events by tenantId")
    void findByTenantFilters() {
        String tenantA = "tenant-A-" + UUID.randomUUID();
        String tenantB = "tenant-B-" + UUID.randomUUID();
        Instant now = Instant.now();
        auditStore.record(stubEvent("u1", tenantA, "sA", now));
        auditStore.record(stubEvent("u2", tenantA, "sA2", now));
        auditStore.record(stubEvent("u3", tenantB, "sB", now));

        var hitsA = auditStore.findByTenant(tenantA, now.minusSeconds(60), now.plusSeconds(60), 100);
        var hitsB = auditStore.findByTenant(tenantB, now.minusSeconds(60), now.plusSeconds(60), 100);
        assertEquals(2, hitsA.size());
        assertEquals(1, hitsB.size());
    }

    @Test
    @DisplayName("findBySession returns events newest-first")
    void findBySessionReturnsNewestFirst() {
        String session = "session-order-" + UUID.randomUUID();
        Instant base = Instant.now();
        AuditEvent older = stubEvent("u", null, session, base.minus(10, ChronoUnit.MINUTES));
        AuditEvent newer = stubEvent("u", null, session, base);
        auditStore.record(older);
        auditStore.record(newer);

        var hits = auditStore.findBySession(session);
        assertEquals(2, hits.size());
        assertEquals(newer.eventId(), hits.get(0).eventId(), "Newest must come first");
        assertEquals(older.eventId(), hits.get(1).eventId());
    }

    @Test
    @DisplayName("countByTimeRange returns the number of events whose timestamp lies in [from, to]")
    void countByTimeRangeMatchesStoredEvents() {
        String marker = "session-count-" + UUID.randomUUID();
        Instant now = Instant.now();
        auditStore.record(stubEvent("u", null, marker, now.minus(2, ChronoUnit.MINUTES)));
        auditStore.record(stubEvent("u", null, marker, now.minus(1, ChronoUnit.MINUTES)));
        auditStore.record(stubEvent("u", null, marker, now));

        long inLastMinute = auditStore.findBySession(marker).stream()
                .filter(e -> !e.timestamp().isBefore(now.minus(75, ChronoUnit.SECONDS)))
                .count();
        assertEquals(2, inLastMinute, "Only the two most-recent events fall inside the last 75 s");

        long anywhere = auditStore.countByTimeRange(now.minus(1, ChronoUnit.DAYS), now.plusSeconds(1));
        assertTrue(anywhere >= 3, "countByTimeRange must include all three stub events at minimum");
    }

    @Test
    @DisplayName("GuardrailEvent list round-trips through the audit store unchanged")
    void guardrailEventsRoundTrip() {
        var guardrails = List.of(
                new AuditEvent.GuardrailEvent("MaxLength", "PASS", null),
                new AuditEvent.GuardrailEvent("PromptInjection", "BLOCK", "matched pattern")
        );
        var withGuardrails = new AuditEvent(
                UUID.randomUUID().toString(), Instant.now(),
                "u", null, "s",
                "msg", "resp", "default-skill", "SEMANTIC", 0.9,
                List.of(), guardrails,
                10, 5, 0.0001, 100L, false, Map.of());
        auditStore.record(withGuardrails);

        AuditEvent fetched = auditStore.findById(withGuardrails.eventId()).orElseThrow();
        assertEquals(2, fetched.guardrailEvents().size());
        assertEquals("BLOCK", fetched.guardrailEvents().get(1).verdict());
        assertEquals("matched pattern", fetched.guardrailEvents().get(1).reason());
    }

    // ─── 3. AuditService.recordRequest ────────────────────────────────

    @Test
    @DisplayName("AuditService.recordRequest builds an AuditEvent with the right fields and stores it")
    void auditServiceMapsRequestToEventIncludingGuardrails() {
        String sessionMarker = "audit-svc-" + UUID.randomUUID();

        var request = AgentRequest.builder()
                .message("hello world")
                .userId("alice")
                .sessionId(sessionMarker)
                .securityContext(new SecurityContext("alice", "acme-tenant", Set.of("user")))
                .build();
        var response = new AgentResponse(
                "hi there", sessionMarker, "default-skill",
                List.of("echo"), RoutingMethod.SEMANTIC, 0.9,
                3, 2, 5, 0.0007, 42L, false);
        var routing = RoutingResult.semantic("default-skill", 0.9);
        var guardrailResults = List.of(
                GuardrailResult.pass("MaxLength"),
                GuardrailResult.block("PromptInjection", "matched pattern"));

        auditService.recordRequest(request, response, routing, guardrailResults);

        var fetched = auditStore.findBySession(sessionMarker);
        assertEquals(1, fetched.size(), "Exactly one event must be recorded");
        AuditEvent e = fetched.get(0);

        assertEquals("alice", e.userId());
        assertEquals("acme-tenant", e.tenantId(), "Tenant must propagate from SecurityContext");
        assertEquals("default-skill", e.skillSelected());
        assertEquals("SEMANTIC", e.routingMethod());
        assertEquals(0.9, e.routingConfidence(), 1e-9);
        assertEquals(List.of("echo"), e.toolsCalled());
        assertEquals(3, e.inputTokens());
        assertEquals(2, e.outputTokens());
        assertEquals(0.0007, e.estimatedCostUsd(), 1e-9);
        assertEquals(42L, e.durationMs());
        assertFalse(e.dryRun());

        // Guardrail mapping
        assertEquals(2, e.guardrailEvents().size());
        assertEquals("MaxLength", e.guardrailEvents().get(0).guardrailName());
        assertEquals("PASS", e.guardrailEvents().get(0).verdict());
        assertEquals("PromptInjection", e.guardrailEvents().get(1).guardrailName());
        assertEquals("BLOCK", e.guardrailEvents().get(1).verdict());
        assertEquals("matched pattern", e.guardrailEvents().get(1).reason());
    }

    @Test
    @DisplayName("AuditService.recordRequest short-circuits silently when audit is disabled in config")
    void auditServiceShortCircuitsWhenDisabled() {
        var disabledProps = new AgentProperties();
        disabledProps.getAudit().setEnabled(false);
        var disabledService = new AuditService(auditStore, disabledProps);

        String sessionMarker = "audit-disabled-" + UUID.randomUUID();
        var request = AgentRequest.builder()
                .message("hello").userId("u").sessionId(sessionMarker).build();
        var response = new AgentResponse(
                "hi", sessionMarker, "default-skill",
                List.of(), RoutingMethod.SEMANTIC, 0.5, 1, 1, 2, 0.0, 1L, false);
        var routing = RoutingResult.semantic("default-skill", 0.5);

        disabledService.recordRequest(request, response, routing, List.of());

        assertTrue(auditStore.findBySession(sessionMarker).isEmpty(),
                "No event must be recorded when agent.audit.enabled=false");
    }

    // ─── 4. Configuration sanity ──────────────────────────────────────

    @Test
    @DisplayName("Both cost-tracking and audit are enabled via application.yml")
    void bothFeaturesEnabledByYaml() {
        assertTrue(properties.getCostTracking().isEnabled(),
                "agent.cost-tracking.enabled should be true in application.yml");
        assertTrue(properties.getAudit().isEnabled(),
                "agent.audit.enabled should be true in application.yml");
        assertFalse(properties.getCostTracking().getPricing().isEmpty(),
                "Pricing map should be populated from application.yml");
    }
}
