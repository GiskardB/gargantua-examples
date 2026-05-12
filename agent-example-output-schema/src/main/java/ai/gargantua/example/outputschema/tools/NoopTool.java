package ai.gargantua.example.outputschema.tools;

import ai.gargantua.core.tool.AgentTool;
import org.springframework.stereotype.Component;

/**
 * Single placeholder {@code @AgentTool} so the SKILL.md linter accepts
 * {@code allowed-tools: [echo]} on every skill in this example.
 */
@Component
public class NoopTool {

    @AgentTool(description = "Echoes back the input — placeholder so allowed-tools resolves.")
    public String echo(String input) {
        return "echo:" + input;
    }
}
