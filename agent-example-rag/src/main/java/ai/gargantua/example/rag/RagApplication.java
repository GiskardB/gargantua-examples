package ai.gargantua.example.rag;

import ai.gargantua.autoconfigure.RagEnricher;
import ai.gargantua.core.rag.VectorStorePort;
import ai.gargantua.core.skill.SkillRegistry;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

/**
 * Main entry point for the RAG example.
 *
 * <p>The explicit {@link #ragEnricher} bean is a workaround for a partial
 * framework gap. v1.2.8 added
 * {@code @AutoConfiguration(after = EmbeddedProfileAutoConfiguration.class)}
 * to {@code RagAutoConfiguration}, but the {@code @Profile("embedded")}
 * stereotype on the embedded auto-config seems to defeat the ordering hint:
 * the {@code @ConditionalOnBean(VectorStorePort.class)} on
 * {@code RagAutoConfiguration#ragEnricher} still fires before the
 * in-memory vector store is registered, and the framework never
 * instantiates the enricher in embedded mode. Defining the bean here
 * forces the wiring at the app level so the test layer can autowire
 * {@link RagEnricher} as documented. Tracked in the per-feature progress
 * memory; will be removed once the framework wiring is fully fixed.</p>
 */
@SpringBootApplication
public class RagApplication {

    public static void main(String[] args) {
        SpringApplication.run(RagApplication.class, args);
    }

    @Bean
    public RagEnricher ragEnricher(VectorStorePort vectorStore, SkillRegistry skillRegistry) {
        return new RagEnricher(vectorStore, skillRegistry);
    }
}
