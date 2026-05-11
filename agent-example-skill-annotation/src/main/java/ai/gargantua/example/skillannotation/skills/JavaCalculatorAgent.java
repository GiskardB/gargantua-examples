package ai.gargantua.example.skillannotation.skills;

import ai.gargantua.core.skill.AgentSkill;
import ai.gargantua.core.tool.AgentTool;
import org.springframework.stereotype.Component;

/**
 * A skill defined inline in Java via {@link AgentSkill}, with no
 * accompanying SKILL.md file. Demonstrates the entire annotation-based
 * authoring path:
 *
 * <ul>
 *   <li>{@code @AgentSkill} carries the metadata (name, description,
 *       version, domain, examples, temperature override).</li>
 *   <li>{@code @Component} makes the class a Spring bean — required so
 *       {@link ai.gargantua.autoconfigure.AgentSkillProcessor} can
 *       discover it during context refresh.</li>
 *   <li>{@code public static final String PROMPT} provides the system
 *       prompt at runtime (Java does not retain Javadoc in bytecode, so
 *       a string field is the framework's chosen convention).</li>
 *   <li>The {@code @AgentTool} methods on this class are auto-detected
 *       and become the skill's {@code allowedTools} — no need to repeat
 *       them in the annotation.</li>
 * </ul>
 *
 * <p>If a {@code SKILL.md} ever ships with the same name, it takes
 * precedence over this class — see the README for details.</p>
 */
@Component
@AgentSkill(
        name = "java-calculator",
        description = "Adds and multiplies two integers — declared inline in Java",
        version = "1.0.0",
        domain = "math",
        temperature = 0.0,
        examples = {
                "What is 17 plus 25?",
                "Compute 6 times 7."
        }
)
public class JavaCalculatorAgent {

    /**
     * The framework reads the skill's system prompt from this field via
     * reflection. The fallback if the field is absent is a generic
     * "You are a helpful assistant for the X skill." — which is what the
     * test suite verifies we are NOT getting.
     */
    public static final String PROMPT = """
            ## Role
            You are a precise arithmetic assistant defined entirely in Java.

            ## Behavior
            - For "X plus Y" / "sum of X and Y", call `add(a, b)`.
            - For "X times Y" / "product of X and Y", call `multiply(a, b)`.
            - Always include the inputs and the returned result in your reply.
            - For anything that is not addition or multiplication, hand off
              to the default-skill.

            ## Scope
            Two-integer addition and multiplication only.
            """;

    public record MultiplyResult(int a, int b, long result) {}

    @AgentTool(description = """
            Adds two integers and returns their sum.
            """)
    public int add(int a, int b) {
        return a + b;
    }

    @AgentTool(description = """
            Multiplies two integers and returns a structured record with
            both inputs and the product.
            """)
    public MultiplyResult multiply(int a, int b) {
        return new MultiplyResult(a, b, (long) a * b);
    }
}
