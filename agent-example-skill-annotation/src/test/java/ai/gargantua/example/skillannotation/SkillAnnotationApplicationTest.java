package ai.gargantua.example.skillannotation;

import ai.gargantua.autoconfigure.AgentSkillProcessor;
import ai.gargantua.core.skill.SkillCard;
import ai.gargantua.core.skill.SkillRegistry;
import ai.gargantua.core.skill.SkillSource;
import ai.gargantua.example.skillannotation.skills.JavaCalculatorAgent;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Pins every observable contract of {@code @AgentSkill}: discovery via
 * {@link AgentSkillProcessor}, metadata fidelity, auto-detected
 * {@code allowedTools}, system-prompt extraction from the {@code PROMPT}
 * field, and propagation of the optional override fields ({@code examples},
 * {@code temperature}).
 *
 * <p>Run with {@code mvn test}. No Docker, no API keys, no Internet.</p>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@ActiveProfiles("embedded")
class SkillAnnotationApplicationTest {

    @Autowired private JavaCalculatorAgent  calculatorAgent;
    @Autowired private AgentSkillProcessor  agentSkillProcessor;
    @Autowired private SkillRegistry        skillRegistry;

    // ── Wiring ──────────────────────────────────────────────────────

    @Test
    @DisplayName("@AgentSkill class is a Spring bean (the @Component is required)")
    void calculatorAgentBeanIsRegistered() {
        assertNotNull(calculatorAgent);
    }

    @Test
    @DisplayName("AgentSkillProcessor discovered exactly the one annotated skill in this app")
    void agentSkillProcessorFoundOurSkill() {
        var discovered = agentSkillProcessor.getDiscoveredSkills();
        assertEquals(1, discovered.size(),
                "expected exactly one @AgentSkill in this example");
    }

    private SkillCard discoveredSkill() {
        return agentSkillProcessor.getDiscoveredSkills().stream()
                .filter(s -> s.meta().name().equals("java-calculator"))
                .findFirst()
                .orElseThrow(() -> new AssertionError("java-calculator not discovered"));
    }

    // ── Metadata round-trip ─────────────────────────────────────────

    @Test
    @DisplayName("Discovered SkillMeta carries the values declared on @AgentSkill")
    void metaCarriesAnnotationValues() {
        var meta = discoveredSkill().meta();
        assertEquals("java-calculator", meta.name());
        assertEquals("1.0.0", meta.version());
        assertEquals("math", meta.domain());
        assertTrue(meta.active(), "active() defaults to true");
        assertTrue(meta.description().contains("inline in Java"),
                "description should round-trip verbatim: " + meta.description());
    }

    @Test
    @DisplayName("Discovered skill is tagged SkillSource.ANNOTATION (vs. FILESYSTEM for SKILL.md)")
    void sourceIsAnnotation() {
        assertEquals(SkillSource.ANNOTATION, discoveredSkill().meta().source());
    }

    // ── Auto-detected @AgentTool methods ───────────────────────────

    @Test
    @DisplayName("allowedTools is auto-populated from @AgentTool methods on the skill class")
    void allowedToolsAreAutoDetected() {
        var tools = discoveredSkill().allowedTools();
        assertTrue(tools.contains("add"),
                "auto-detection should pick up the @AgentTool 'add' method: " + tools);
        assertTrue(tools.contains("multiply"),
                "auto-detection should pick up the @AgentTool 'multiply' method: " + tools);
        assertEquals(2, tools.size(),
                "no other @AgentTool method on the class — got: " + tools);
    }

    // ── System prompt comes from the PROMPT field, not the fallback ──

    @Test
    @DisplayName("systemPrompt is read from the public static final String PROMPT field, not the generic fallback")
    void systemPromptFromPromptField() {
        String prompt = discoveredSkill().systemPrompt();
        assertTrue(prompt.contains("precise arithmetic assistant"),
                "PROMPT field should be wired into systemPrompt: " + prompt);
        assertFalse(prompt.startsWith("You are a helpful assistant"),
                "this is the AgentSkillProcessor fallback — should NOT be hit when PROMPT is present");
    }

    // ── Optional override fields ───────────────────────────────────

    @Test
    @DisplayName("examples array on @AgentSkill propagates to the SkillCard")
    void examplesPropagate() {
        var examples = discoveredSkill().examples();
        assertEquals(2, examples.size());
        assertEquals("What is 17 plus 25?", examples.get(0));
    }

    @Test
    @DisplayName("temperature override on @AgentSkill propagates to the SkillCard")
    void temperatureOverridePropagates() {
        Double temp = discoveredSkill().temperature();
        assertNotNull(temp, "annotation declared temperature=0.0 — should propagate, not stay null");
        assertEquals(0.0, temp, 0.0001);
    }

    // ── Integration with SkillRegistry (1.2.7+) ────────────────────

    @Test
    @DisplayName("SkillRegistry.findMeta sees the annotated skill (1.2.7+ wiring)")
    void skillRegistryFindsAnnotatedSkill() {
        var meta = skillRegistry.findMeta("java-calculator");
        assertTrue(meta.isPresent(),
                "AnnotatedSkillRegistry should expose @AgentSkill classes");
        assertEquals(SkillSource.ANNOTATION, meta.get().source());
    }

    @Test
    @DisplayName("SkillRegistry.load returns the full SkillCard built from the @AgentSkill class")
    void skillRegistryLoadsAnnotatedSkill() {
        var card = skillRegistry.load("java-calculator");
        assertEquals("java-calculator", card.meta().name());
        assertTrue(card.systemPrompt().contains("precise arithmetic assistant"));
        assertTrue(card.allowedTools().contains("add"));
        assertTrue(card.allowedTools().contains("multiply"));
    }
}
