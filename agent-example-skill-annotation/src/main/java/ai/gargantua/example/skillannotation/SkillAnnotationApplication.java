package ai.gargantua.example.skillannotation;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Main entry point for the @AgentSkill example.
 *
 * <p>No explicit imports needed: as of framework v1.2.7 the
 * {@code AgentSkillProcessor} is registered by
 * {@code SkillRegistryAutoConfiguration} and its discovered skills are
 * wired into the {@code SkillRegistry} composite alongside the
 * filesystem / classpath-jar sources. SKILL.md files still win on name
 * collisions.</p>
 */
@SpringBootApplication
public class SkillAnnotationApplication {

    public static void main(String[] args) {
        SpringApplication.run(SkillAnnotationApplication.class, args);
    }
}
