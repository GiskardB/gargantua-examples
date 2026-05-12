package ai.gargantua.example.a2a.tools;

import ai.gargantua.core.tool.AgentTool;
import org.springframework.stereotype.Component;

/**
 * A trivial tool — exists so the AgentCardService can surface a
 * non-empty {@code allowedTools} list in the A2A skill tags.
 */
@Component
public class BillingTool {

    @AgentTool(description = "Look up an invoice by id (stubbed).")
    public String lookupInvoice(String invoiceId) {
        return "{\"invoiceId\":\"" + invoiceId + "\",\"amount\":42.00,\"status\":\"PAID\"}";
    }
}
