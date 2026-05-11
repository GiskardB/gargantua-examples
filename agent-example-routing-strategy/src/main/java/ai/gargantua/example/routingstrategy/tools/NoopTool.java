package ai.gargantua.example.routingstrategy.tools;

import ai.gargantua.core.tool.AgentTool;
import org.springframework.stereotype.Component;

/**
 * Single placeholder {@code @AgentTool} so the SKILL.md linter can
 * resolve {@code allowed-tools: [echo]} on every skill in this example.
 * The actual focus of the module is the routing layer, not tool dispatch.
 */
@Component
public class NoopTool {

    @AgentTool(description = "Echoes back the input — placeholder so allowed-tools resolves.")
    public String echo(String input) {
        return "echo:" + input;
    }
}
