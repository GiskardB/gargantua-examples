package ai.gargantua.example.skillannotation;

import ai.gargantua.autoconfigure.AgentSkillProcessor;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Import;

/**
 * Main entry point for the @AgentSkill example.
 *
 * <p>The explicit {@code @Import(AgentSkillProcessor.class)} is a
 * workaround for an open framework gap: as of v1.2.6 the framework's
 * {@code AgentSkillProcessor} carries a {@code @Component} annotation
 * but is NOT registered by any auto-configuration {@code @Bean} factory.
 * Component scan picks up only the application's own packages by default,
 * so without the explicit import the processor never gets instantiated
 * and {@code @AgentSkill} discovery silently does nothing.</p>
 *
 * <p>Removing the import once the framework wires the processor into the
 * regular auto-configuration chain is tracked in the per-feature
 * examples progress memory.</p>
 */
@SpringBootApplication
@Import(AgentSkillProcessor.class)
public class SkillAnnotationApplication {

    public static void main(String[] args) {
        SpringApplication.run(SkillAnnotationApplication.class, args);
    }
}
