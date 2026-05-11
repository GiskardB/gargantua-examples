package ai.gargantua.example.toolapproval.tools;

import ai.gargantua.core.tool.AgentTool;
import ai.gargantua.core.tool.RequiresApproval;
import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Toy bank tool that demonstrates the {@link RequiresApproval} annotation.
 *
 * <p>Two methods on purpose:</p>
 * <ul>
 *   <li>{@link #transfer(String, String, long)} — flagged with
 *       {@code @RequiresApproval(dangerous = true)} so the framework's
 *       streaming chat path pauses and asks a human reviewer before the
 *       tool body actually runs.</li>
 *   <li>{@link #getBalance(String)} — unflagged control method. Identical
 *       wiring but no approval gate; used by the test suite to prove that
 *       only the approval-annotated method carries the metadata.</li>
 * </ul>
 *
 * <p>The "balances" are kept in a {@link ConcurrentHashMap} so tests can
 * assert state changes without a real database.</p>
 */
@Component
public class MoneyTransferTool {

    private final ConcurrentMap<String, Long> balances = new ConcurrentHashMap<>();

    public record TransferResult(String from, String to, long amount,
                                 long fromBalance, long toBalance) {}

    /** Helpers for tests. */
    public void seedBalance(String account, long amount) {
        balances.put(account, amount);
    }
    public long balanceOf(String account) {
        return balances.getOrDefault(account, 0L);
    }
    public void resetBalances() {
        balances.clear();
    }

    @AgentTool(description = """
            Returns the current balance of an account, in cents. Read-only —
            no approval required.
            """)
    public long getBalance(String account) {
        return balanceOf(account);
    }

    @AgentTool(description = """
            Transfers `amount` cents from `from` to `to`. Mutating + financially
            sensitive, so the framework's streaming chat path will pause and
            ask a human to approve the action before this method actually runs.
            """)
    @RequiresApproval(
            message = "About to transfer money — please review the amount and accounts before approving.",
            showParameters = {"from", "to", "amount"},
            dangerous = true
    )
    public TransferResult transfer(String from, String to, long amount) {
        if (amount <= 0) {
            throw new IllegalArgumentException("amount must be positive, got " + amount);
        }
        long fromBalance = balances.getOrDefault(from, 0L);
        if (fromBalance < amount) {
            throw new IllegalStateException(
                    "insufficient funds in " + from + " (balance=" + fromBalance + ", needed=" + amount + ")");
        }
        long newFrom = fromBalance - amount;
        long newTo   = balances.getOrDefault(to, 0L) + amount;
        balances.put(from, newFrom);
        balances.put(to, newTo);
        return new TransferResult(from, to, amount, newFrom, newTo);
    }
}
