package ai.gargantua.example.toolbasics.tools;

import ai.gargantua.core.tool.AgentTool;
import org.springframework.stereotype.Component;

/**
 * Pure-arithmetic tool used to demonstrate the bare minimum required to
 * expose a method to the agent: a Spring bean + {@link AgentTool} on a
 * public method.
 *
 * <p>The three methods deliberately cover all three knobs of the
 * {@link AgentTool} annotation:</p>
 * <ul>
 *   <li>{@link #add(int, int)} — only {@code description} is set. Tool name
 *       defaults to the method name {@code "add"}. Return type is a primitive
 *       {@code int} → ToolRegistry serialises it as JSON {@code "5"}.</li>
 *   <li>{@link #multiply(int, int)} — same defaults, but returns a record so
 *       the LLM-facing payload is structured JSON like
 *       {@code {"a":3,"b":4,"result":12}}.</li>
 *   <li>{@link #power(int, int)} — overrides the tool name via
 *       {@code name = "pow"}. The Java method is still {@code power}, but
 *       the registry exposes it to the LLM as {@code pow}. Also sets
 *       {@code parallelizable = false} to demo the third knob.</li>
 * </ul>
 *
 * <p>There is no network call, no caching, no retry — those concerns are
 * shown in the {@code agent-example-tool-retry}, {@code -tool-cache}, etc.
 * sibling examples. This module is intentionally the smallest thing that
 * proves the {@code @AgentTool} contract end-to-end.</p>
 */
@Component
public class CalculatorTool {

    /** Structured return used by {@link #multiply(int, int)}. */
    public record MultiplyResult(int a, int b, long result) {}

    @AgentTool(description = """
            Adds two integers and returns their sum. Use whenever the user
            asks to add, sum, total, or combine two numbers.
            """)
    public int add(int a, int b) {
        return a + b;
    }

    @AgentTool(description = """
            Multiplies two integers and returns a structured result containing
            both inputs and the product. Use for multiplication / "times" /
            "product of" questions.
            """)
    public MultiplyResult multiply(int a, int b) {
        return new MultiplyResult(a, b, (long) a * b);
    }

    /**
     * Demonstrates the {@code name} override: the method is {@code power}
     * but the LLM sees this tool as {@code pow}. Also flagged
     * {@code parallelizable = false} purely to demonstrate the third knob —
     * arithmetic is obviously parallel-safe, but the orchestrator will treat
     * this one as serial to make the difference observable in tests.
     */
    @AgentTool(
            name = "pow",
            description = """
                    Raises an integer base to a non-negative integer exponent
                    (0–30) and returns a long. Use for "X to the power of Y",
                    "X^Y", or "square / cube of X" questions.
                    """,
            parallelizable = false
    )
    public long power(int base, int exponent) {
        if (exponent < 0 || exponent > 30) {
            throw new IllegalArgumentException("exponent must be in [0, 30], got " + exponent);
        }
        long result = 1L;
        for (int i = 0; i < exponent; i++) {
            result *= base;
        }
        return result;
    }
}
