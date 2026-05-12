package ai.gargantua.example.costandaudit.tools;

import ai.gargantua.core.tool.AgentTool;
import org.springframework.stereotype.Component;

/** Placeholder so the SKILL.md linter resolves allowed-tools. */
@Component
public class NoopTool {

    @AgentTool(description = "Echoes back the input — placeholder.")
    public String echo(String input) {
        return "echo:" + input;
    }
}
