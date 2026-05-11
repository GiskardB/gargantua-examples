package ai.gargantua.example.skillfilesystem.tools;

import ai.gargantua.core.tool.AgentTool;
import org.springframework.stereotype.Component;

/**
 * Minimal placeholder tool referenced by {@code allowed-tools} in the
 * SKILL.md files. The point of this example is the SKILL.md parser,
 * not the tool itself — but the linter requires {@code allowed-tools}
 * entries to resolve to real {@code @AgentTool} methods, so we ship
 * a couple of trivial ones.
 */
@Component
public class MarketTool {

    @AgentTool(description = "Returns a fixed market snapshot for the given ticker — placeholder")
    public String lookup(String ticker) {
        return "ticker=" + ticker + ";price=100.0";
    }

    @AgentTool(description = "Returns a fixed analyst note for the given ticker — placeholder")
    public String analyze(String ticker) {
        return "ticker=" + ticker + ";note=neutral outlook";
    }
}
