package ai.gargantua.example.agentsflow.flows;

import ai.gargantua.core.flow.AgentsFlow;
import ai.gargantua.core.flow.FlowDefinition;
import org.springframework.stereotype.Component;

/**
 * Four {@link AgentsFlow}-annotated methods, each one demonstrating one
 * branch of the {@link FlowDefinition} DSL:
 *
 * <ol>
 *   <li>{@link #simpleSequential(FlowDefinition)} — three sequential
 *       steps; each step's output is fed as input to the next.</li>
 *   <li>{@link #sequentialWithInstruction(FlowDefinition)} — same
 *       sequential shape but with a per-step instruction prepended to
 *       the step input by {@code FlowExecutor#buildStepInput}.</li>
 *   <li>{@link #loopUntilDone(FlowDefinition)} — a single {@code LOOP}
 *       step with a {@code maxIterations} budget; the executor exits
 *       early on {@code [DONE]} or {@code [SATISFIED]}.</li>
 *   <li>{@link #mapReduce(FlowDefinition)} — fan-out / fan-in shape:
 *       one sequential plan step, two {@code PARALLEL} workers, one
 *       sequential reduce step. The executor collects the two parallel
 *       outputs and concatenates them as input to the reducer.</li>
 * </ol>
 *
 * <p>The skill names below ({@code planner}, {@code coder}, etc.) are
 * intentionally placeholders — flow discovery does NOT validate that the
 * referenced skills exist, that happens at execution time. The integration
 * tests below only exercise the discovery + metadata path, which is fully
 * verifiable without any skill, LLM, or external infrastructure.</p>
 */
@Component
public class MyFlows {

    @AgentsFlow(name = "code-review", description = "Plan, code, then review — sequential pipeline")
    public void simpleSequential(FlowDefinition flow) {
        flow.step("planner")
            .step("coder")
            .step("reviewer");
    }

    @AgentsFlow(name = "translate-and-summarize",
            description = "Translate the input to English, then summarise the translation")
    public void sequentialWithInstruction(FlowDefinition flow) {
        flow.step("translator", "Translate the user message to English.")
            .step("summariser", "Produce a 2-sentence summary of the previous step's output.");
    }

    @AgentsFlow(name = "refine-until-done",
            description = "Refine the draft up to 5 times, exit early on [DONE]")
    public void loopUntilDone(FlowDefinition flow) {
        flow.loop("editor", 5);
    }

    @AgentsFlow(name = "map-reduce",
            description = "Plan, run two analyses in parallel, then merge into a verdict")
    public void mapReduce(FlowDefinition flow) {
        flow.step("planner")
            .parallel("analyst-sentiment", "analyst-fundamentals")
            .step("reducer");
    }
}
