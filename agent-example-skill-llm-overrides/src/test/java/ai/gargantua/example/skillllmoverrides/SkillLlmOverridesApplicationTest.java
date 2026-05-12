package ai.gargantua.example.skillllmoverrides;

import ai.gargantua.core.orchestrator.AgentRequest;
import ai.gargantua.core.orchestrator.OrchestratorEngine;
import ai.gargantua.core.skill.SkillCard;
import ai.gargantua.core.skill.SkillRegistry;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.model.ModelProvider;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.test.context.ActiveProfiles;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Pins all three skill-level LLM overrides end-to-end:
 *
 * <ul>
 *   <li>{@code metadata.preferred-model} → resolved by
 *       {@code LlmProviderFactory.resolveModelAlias} into a custom alias
 *       (we register a dedicated mock {@code ChatModel} bean per alias and
 *       check which one received the call).</li>
 *   <li>{@code metadata.temperature} → applied via
 *       {@code ChatRequest.Builder.temperature(...)} (v1.2.17+).</li>
 *   <li>{@code metadata.max-tokens} → applied via
 *       {@code ChatRequest.Builder.maxOutputTokens(...)} (v1.2.17+).</li>
 * </ul>
 *
 * <p>The test substitutes the {@code primary} and {@code precise} aliases with
 * {@link RecordingChatModel} beans named after the alias — the same lookup
 * mechanism {@code LlmProviderFactory.lookupChatModelBean} uses for any
 * user-provided model. No real LLM is invoked.</p>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK,
        classes = {SkillLlmOverridesApplication.class,
                SkillLlmOverridesApplicationTest.MockedModelsConfig.class})
@ActiveProfiles("embedded")
class SkillLlmOverridesApplicationTest {

    @Autowired private OrchestratorEngine orchestrator;
    @Autowired private SkillRegistry skillRegistry;
    @Autowired private RecordingChatModel primary;
    @Autowired private RecordingChatModel precise;

    @BeforeEach
    void resetRecordings() {
        primary.received.clear();
        precise.received.clear();
    }

    private AgentRequest forceSkill(String skillName, String message) {
        return AgentRequest.builder()
                .message(message)
                .userId("alice")
                .sessionId("session-" + skillName)
                .forceSkill(skillName)
                .build();
    }

    // ─── 1. SKILL.md frontmatter round-trip ──────────────────────────

    @Test
    @DisplayName("default-skill SKILL.md frontmatter has no temperature / max-tokens / preferred-model")
    void defaultSkillHasNoOverrides() {
        SkillCard card = skillRegistry.load("default-skill");
        assertNull(card.temperature(), "default-skill must not declare temperature");
        assertNull(card.maxTokens(),    "default-skill must not declare max-tokens");
        assertNull(card.preferredModel(), "default-skill must not declare preferred-model");
    }

    @Test
    @DisplayName("precise-skill SKILL.md frontmatter populates SkillCard with all three overrides")
    void preciseSkillCarriesAllOverrides() {
        SkillCard card = skillRegistry.load("precise-skill");
        assertEquals(0.0, card.temperature(), 1e-9);
        assertEquals(50,  card.maxTokens());
        assertEquals("precise", card.preferredModel());
    }

    @Test
    @DisplayName("creative-skill SKILL.md frontmatter sets temperature + max-tokens but no preferred-model")
    void creativeSkillCarriesPartialOverrides() {
        SkillCard card = skillRegistry.load("creative-skill");
        assertEquals(0.9, card.temperature(), 1e-9);
        assertEquals(200, card.maxTokens());
        assertNull(card.preferredModel(), "creative-skill must NOT declare a preferred-model");
    }

    // ─── 2. preferred-model resolution ────────────────────────────────

    @Test
    @DisplayName("preferred-model=precise routes the LLM call to the `precise` ChatModel bean (not `primary`)")
    void preferredModelRoutesToCustomAlias() {
        orchestrator.invoke(forceSkill("precise-skill", "translate this clause"));

        assertEquals(0, primary.received.size(), "primary alias must NOT be called when a preferred-model is set");
        assertEquals(1, precise.received.size(), "precise alias must receive exactly one call");
    }

    @Test
    @DisplayName("Skill without preferred-model stays on the primary ChatModel bean")
    void noPreferredModelStaysOnPrimary() {
        orchestrator.invoke(forceSkill("creative-skill", "draft a hero copy"));

        assertEquals(1, primary.received.size(), "primary alias must receive the call");
        assertEquals(0, precise.received.size(), "precise alias must NOT be called");
    }

    @Test
    @DisplayName("default-skill (no preferred-model, no overrides) uses the primary alias")
    void defaultSkillUsesPrimary() {
        orchestrator.invoke(forceSkill("default-skill", "hello"));

        assertEquals(1, primary.received.size());
        assertEquals(0, precise.received.size());
    }

    // ─── 3. temperature override applied to the ChatRequest ──────────

    @Test
    @DisplayName("precise-skill temperature=0.0 is applied to every ChatRequest sent to the `precise` alias")
    void preciseSkillTemperatureIsApplied() {
        orchestrator.invoke(forceSkill("precise-skill", "translate this clause"));

        ChatRequest captured = precise.received.get(0);
        assertEquals(0.0, captured.parameters().temperature(), 1e-9,
                "ChatRequest.parameters().temperature() must reflect the SKILL.md value");
    }

    @Test
    @DisplayName("creative-skill temperature=0.9 is applied to every ChatRequest sent to the `primary` alias")
    void creativeSkillTemperatureIsApplied() {
        orchestrator.invoke(forceSkill("creative-skill", "draft a hero copy"));

        ChatRequest captured = primary.received.get(0);
        assertEquals(0.9, captured.parameters().temperature(), 1e-9);
    }

    @Test
    @DisplayName("default-skill leaves ChatRequest.parameters().temperature() null (no override)")
    void defaultSkillDoesNotOverrideTemperature() {
        orchestrator.invoke(forceSkill("default-skill", "hello"));

        ChatRequest captured = primary.received.get(0);
        assertNull(captured.parameters().temperature(),
                "When no skill-level temperature is set, the field must remain null on the request");
    }

    // ─── 4. max-tokens override applied to the ChatRequest ───────────

    @Test
    @DisplayName("precise-skill max-tokens=50 is applied to every ChatRequest sent to the `precise` alias")
    void preciseSkillMaxTokensIsApplied() {
        orchestrator.invoke(forceSkill("precise-skill", "translate this clause"));

        ChatRequest captured = precise.received.get(0);
        assertEquals(50, captured.parameters().maxOutputTokens(),
                "ChatRequest.parameters().maxOutputTokens() must reflect the SKILL.md value");
    }

    @Test
    @DisplayName("creative-skill max-tokens=200 is applied to every ChatRequest sent to the `primary` alias")
    void creativeSkillMaxTokensIsApplied() {
        orchestrator.invoke(forceSkill("creative-skill", "draft a hero copy"));

        ChatRequest captured = primary.received.get(0);
        assertEquals(200, captured.parameters().maxOutputTokens());
    }

    @Test
    @DisplayName("default-skill leaves ChatRequest.parameters().maxOutputTokens() null (no override)")
    void defaultSkillDoesNotOverrideMaxTokens() {
        orchestrator.invoke(forceSkill("default-skill", "hello"));

        ChatRequest captured = primary.received.get(0);
        assertNull(captured.parameters().maxOutputTokens(),
                "When no skill-level max-tokens is set, the field must remain null on the request");
    }

    // ─── 5. ChatModel beans / @TestConfiguration ─────────────────────

    /**
     * Test-only ChatModel that records every {@link ChatRequest} it receives
     * and replies with a fixed AiMessage so the orchestrator returns cleanly.
     * Registered as a Spring bean with the alias name (`primary` / `precise`)
     * so {@code LlmProviderFactory.lookupChatModelBean} picks it up instead
     * of trying to build a real OpenAI client.
     */
    public static class RecordingChatModel implements ChatModel {
        public final List<ChatRequest> received = new ArrayList<>();

        @Override
        public ChatResponse chat(ChatRequest chatRequest) {
            received.add(chatRequest);
            return ChatResponse.builder()
                    .aiMessage(AiMessage.from("ok"))
                    .build();
        }

        @Override
        public ModelProvider provider() {
            return ModelProvider.OTHER;
        }
    }

    @org.springframework.boot.test.context.TestConfiguration
    static class MockedModelsConfig {
        @Bean(name = "primary")
        RecordingChatModel primary() {
            return new RecordingChatModel();
        }

        @Bean(name = "precise")
        RecordingChatModel precise() {
            return new RecordingChatModel();
        }
    }
}
