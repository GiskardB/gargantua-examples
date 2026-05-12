package ai.gargantua.example.mcp.tools;

import ai.gargantua.core.tool.AgentTool;
import org.springframework.stereotype.Component;

/**
 * A trivial tool — exists only so the {@code CapabilitiesMcpResource}
 * has at least one non-empty {@code agentTools} entry to expose, and the
 * skill linter resolves the SKILL.md {@code allowed-tools} reference.
 */
@Component
public class WeatherTool {

    @AgentTool(description = "Return a stubbed weather report for a given city.")
    public String getWeather(String city) {
        return "Sunny, 22°C in " + (city == null ? "unknown" : city) + ".";
    }
}
