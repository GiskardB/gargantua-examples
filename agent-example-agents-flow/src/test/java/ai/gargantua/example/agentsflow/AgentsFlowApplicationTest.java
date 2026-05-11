package ai.gargantua.example.agentsflow;

import ai.gargantua.autoconfigure.FlowRegistry;
import ai.gargantua.core.flow.FlowDefinition;
import ai.gargantua.core.flow.FlowDefinition.FlowStep;
import ai.gargantua.core.flow.FlowDefinition.StepType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Pins the discovery + metadata contract of {@code @AgentsFlow}. The four
 * annotated methods in {@link ai.gargantua.example.agentsflow.flows.MyFlows}
 * must each register one {@link FlowDefinition} with the right name,
 * description and step shape.
 *
 * <p>Live execution (which sends every step through an
 * {@code OrchestratorEngine.invoke} call and therefore needs an LLM)
 * is out of scope here — see the README's "Run it" section for the
 * docker-compose recipe.</p>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@ActiveProfiles("embedded")
class AgentsFlowApplicationTest {

    @Autowired private FlowRegistry flowRegistry;

    // ── 1. Discovery ────────────────────────────────────────────────

    @Test
    @DisplayName("All four @AgentsFlow methods are registered as FlowDefinitions")
    void allFourFlowsDiscovered() {
        List<String> names = flowRegistry.getAll().stream()
                .map(FlowDefinition::name)
                .toList();
        assertEquals(4, flowRegistry.getAll().size(),
                "expected exactly 4 registered flows, got: " + names);
        assertTrue(names.contains("code-review"));
        assertTrue(names.contains("translate-and-summarize"));
        assertTrue(names.contains("refine-until-done"));
        assertTrue(names.contains("map-reduce"));
    }

    @Test
    @DisplayName("FlowRegistry.get(known) returns the FlowDefinition; unknown returns Optional.empty()")
    void getByNameOptional() {
        assertTrue(flowRegistry.get("code-review").isPresent());
        Optional<FlowDefinition> missing = flowRegistry.get("no-such-flow");
        assertTrue(missing.isEmpty());
    }

    // ── 2. Metadata round-trip ─────────────────────────────────────

    @Test
    @DisplayName("Annotation name + description are preserved on FlowDefinition")
    void nameAndDescriptionRoundTrip() {
        FlowDefinition flow = flowRegistry.get("code-review").orElseThrow();
        assertEquals("code-review", flow.name());
        assertEquals("Plan, code, then review — sequential pipeline", flow.description());
    }

    // ── 3. SEQUENTIAL steps ────────────────────────────────────────

    @Test
    @DisplayName("Sequential flow registers steps in the declared order, all SEQUENTIAL, no instruction")
    void sequentialStepsRoundTrip() {
        List<FlowStep> steps = flowRegistry.get("code-review").orElseThrow().steps();

        assertEquals(3, steps.size());
        assertEquals(List.of("planner", "coder", "reviewer"),
                steps.stream().map(FlowStep::skillName).toList());
        for (FlowStep s : steps) {
            assertEquals(StepType.SEQUENTIAL, s.type(),
                    "step " + s.skillName() + " should be SEQUENTIAL: " + s);
            assertNull(s.instruction(), "no instruction was declared on " + s.skillName());
        }
    }

    @Test
    @DisplayName("Sequential .step(name, instruction) carries the instruction on the step")
    void sequentialStepInstructionPropagates() {
        List<FlowStep> steps = flowRegistry.get("translate-and-summarize").orElseThrow().steps();

        assertEquals(2, steps.size());
        assertEquals("translator", steps.get(0).skillName());
        assertTrue(steps.get(0).instruction().contains("Translate"),
                "instruction should round-trip on step 1: " + steps.get(0).instruction());
        assertEquals("summariser", steps.get(1).skillName());
        assertTrue(steps.get(1).instruction().contains("summary"));
    }

    // ── 4. LOOP step ───────────────────────────────────────────────

    @Test
    @DisplayName(".loop(name, n) registers a single LOOP step with maxIterations=n")
    void loopStepCarriesMaxIterations() {
        List<FlowStep> steps = flowRegistry.get("refine-until-done").orElseThrow().steps();

        assertEquals(1, steps.size(),
                ".loop() should produce exactly one step entry, got: " + steps);
        FlowStep loop = steps.get(0);
        assertEquals(StepType.LOOP, loop.type());
        assertEquals("editor", loop.skillName());
        assertEquals(5, loop.maxIterations());
    }

    // ── 5. PARALLEL block ──────────────────────────────────────────

    @Test
    @DisplayName(".parallel(a, b) registers each parallel skill as its own PARALLEL step (executor coalesces them)")
    void parallelBlockExpandsToOneStepPerSkill() {
        List<FlowStep> steps = flowRegistry.get("map-reduce").orElseThrow().steps();

        // Expected order: SEQUENTIAL planner, PARALLEL analyst-sentiment,
        // PARALLEL analyst-fundamentals, SEQUENTIAL reducer.
        assertEquals(4, steps.size(), "got: " + steps);

        assertEquals(StepType.SEQUENTIAL, steps.get(0).type());
        assertEquals("planner", steps.get(0).skillName());

        assertEquals(StepType.PARALLEL, steps.get(1).type());
        assertEquals("analyst-sentiment", steps.get(1).skillName());

        assertEquals(StepType.PARALLEL, steps.get(2).type());
        assertEquals("analyst-fundamentals", steps.get(2).skillName());

        assertEquals(StepType.SEQUENTIAL, steps.get(3).type());
        assertEquals("reducer", steps.get(3).skillName());
    }

    @Test
    @DisplayName("Adjacent PARALLEL steps form a coherent block that the executor will run together")
    void consecutiveParallelStepsAreContiguous() {
        List<FlowStep> steps = flowRegistry.get("map-reduce").orElseThrow().steps();

        int firstParallel = -1;
        int lastParallel = -1;
        for (int i = 0; i < steps.size(); i++) {
            if (steps.get(i).type() == StepType.PARALLEL) {
                if (firstParallel < 0) firstParallel = i;
                lastParallel = i;
            }
        }
        // No SEQUENTIAL step in the middle of the parallel block.
        for (int i = firstParallel; i <= lastParallel; i++) {
            assertEquals(StepType.PARALLEL, steps.get(i).type(),
                    "PARALLEL block must not be interrupted: step " + i + " = " + steps.get(i));
        }
    }
}
