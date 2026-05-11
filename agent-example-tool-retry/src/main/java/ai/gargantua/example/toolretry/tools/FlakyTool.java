package ai.gargantua.example.toolretry.tools;

import ai.gargantua.core.tool.AgentTool;
import ai.gargantua.core.tool.ToolRetry;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Pure in-process tool used to exercise every branch of {@link ToolRetry}:
 *
 * <ol>
 *   <li>{@link #flakyAdd(int, int)} — fails the first
 *       {@code failuresBeforeSuccess} attempts with {@link IOException} (the
 *       default {@code retryOn}), then succeeds. Demonstrates the happy
 *       path: a flaky integration that the framework retries
 *       transparently until it works.</li>
 *   <li>{@link #alwaysFail(String)} — always throws {@link IOException}.
 *       After {@code maxAttempts} the retry budget is exhausted and the
 *       registry returns a structured {@code {"error":"…"}} payload.</li>
 *   <li>{@link #failWithNonRetriedException(String)} — throws an exception
 *       that is NOT in {@code retryOn} and NOT in {@code abortOn}. Resilience4j's
 *       predicate falls through to "not retried". One invocation only.</li>
 *   <li>{@link #failWithAbortException(String)} — uses a wide
 *       {@code retryOn = Exception.class} that would normally retry
 *       everything, but the thrown {@link IllegalStateException} matches
 *       the configured {@code abortOn} and short-circuits the retry loop.
 *       One invocation only.</li>
 * </ol>
 *
 * <p>Counters per method let the test layer assert exactly how many times
 * each method ran. Call {@link #reset()} between tests.</p>
 */
@Component
public class FlakyTool {

    /** Result returned by {@link #flakyAdd(int, int)} once it finally succeeds. */
    public record Sum(int a, int b, int result, int attemptNumber) {}

    private final AtomicInteger flakyAddInvocations          = new AtomicInteger(0);
    private final AtomicInteger alwaysFailInvocations        = new AtomicInteger(0);
    private final AtomicInteger nonRetriedInvocations        = new AtomicInteger(0);
    private final AtomicInteger abortOnInvocations           = new AtomicInteger(0);

    /**
     * Number of synthetic failures {@link #flakyAdd(int, int)} produces before
     * succeeding. Default {@code 2} → the third attempt returns the real sum.
     * Tests can tune this with {@link #setFailuresBeforeSuccess(int)}.
     */
    private volatile int failuresBeforeSuccess = 2;

    /** Reset counters and behaviour between tests. */
    public void reset() {
        flakyAddInvocations.set(0);
        alwaysFailInvocations.set(0);
        nonRetriedInvocations.set(0);
        abortOnInvocations.set(0);
        failuresBeforeSuccess = 2;
    }

    public int getFlakyAddInvocations()    { return flakyAddInvocations.get(); }
    public int getAlwaysFailInvocations()  { return alwaysFailInvocations.get(); }
    public int getNonRetriedInvocations()  { return nonRetriedInvocations.get(); }
    public int getAbortOnInvocations()     { return abortOnInvocations.get(); }
    public void setFailuresBeforeSuccess(int n) { this.failuresBeforeSuccess = n; }

    // ── 1. Eventually succeeds ──────────────────────────────────────

    @AgentTool(description = """
            Adds two integers. The first 2 attempts always fail with a
            simulated transient IOException; the 3rd attempt returns the
            real sum. Use to demonstrate transparent retry on flaky calls.
            """)
    @ToolRetry(
            maxAttempts = 3,
            waitDurationMs = 10,
            backoffMultiplier = 2.0,
            retryOn = {IOException.class}
    )
    public Sum flakyAdd(int a, int b) throws IOException {
        int n = flakyAddInvocations.incrementAndGet();
        if (n <= failuresBeforeSuccess) {
            throw new IOException(
                    "simulated transient failure on attempt " + n
                    + " (will succeed on attempt " + (failuresBeforeSuccess + 1) + ")");
        }
        return new Sum(a, b, a + b, n);
    }

    // ── 2. Retries exhausted ────────────────────────────────────────

    @AgentTool(description = """
            Always throws an IOException — used to demonstrate what happens
            when the retry budget is exhausted: the registry surfaces a
            structured {"error":"…"} payload rather than throwing.
            """)
    @ToolRetry(
            maxAttempts = 3,
            waitDurationMs = 10,
            retryOn = {IOException.class}
    )
    public String alwaysFail(String reason) throws IOException {
        alwaysFailInvocations.incrementAndGet();
        throw new IOException("permanent failure: " + reason);
    }

    // ── 3. Exception type not in retryOn ────────────────────────────

    @AgentTool(description = """
            Throws an IllegalStateException, which is neither in retryOn
            (defaults to IOException) nor in abortOn (defaults to
            IllegalArgumentException). Resilience4j's predicate falls
            through and the tool is NOT retried — one invocation only.
            """)
    @ToolRetry(
            maxAttempts = 3,
            waitDurationMs = 10,
            retryOn = {IOException.class}
    )
    public String failWithNonRetriedException(String reason) {
        nonRetriedInvocations.incrementAndGet();
        throw new IllegalStateException("not in retryOn: " + reason);
    }

    // ── 4. abortOn short-circuits retries ───────────────────────────

    @AgentTool(description = """
            Uses a wide retryOn=Exception that would normally retry
            anything, but configures abortOn={IllegalStateException} which
            wins. The thrown IllegalStateException short-circuits the
            retry loop — one invocation only.
            """)
    @ToolRetry(
            maxAttempts = 3,
            waitDurationMs = 10,
            retryOn = {Exception.class},
            abortOn = {IllegalStateException.class}
    )
    public String failWithAbortException(String reason) {
        abortOnInvocations.incrementAndGet();
        throw new IllegalStateException("aborted: " + reason);
    }
}
