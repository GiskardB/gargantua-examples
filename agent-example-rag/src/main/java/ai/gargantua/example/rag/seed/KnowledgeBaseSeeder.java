package ai.gargantua.example.rag.seed;

import ai.gargantua.core.rag.VectorStorePort;
import ai.gargantua.memory.adapters.inmemory.InMemoryVectorStore;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Boot-time seeder for the in-memory vector store.
 *
 * <p>The framework's {@code EmbeddedProfileAutoConfiguration} wires an
 * {@link InMemoryVectorStore} bean in embedded mode. That class exposes
 * a public {@code addChunk(collection, content, source)} method beyond
 * the {@link VectorStorePort} interface — we use it here at
 * {@code @PostConstruct} time to populate a {@code support-faq}
 * collection with a handful of policy snippets.</p>
 *
 * <p>The collection name MUST match the {@code metadata.knowledge-base}
 * value declared in {@code support-skill/SKILL.md} — otherwise the
 * {@code RagEnricher} will search an empty collection and return
 * nothing.</p>
 *
 * <p>Production apps would replace this seeder with an ingest pipeline
 * driven by document chunkers / embeddings against a real vector
 * database (pgvector, Qdrant, Milvus). The retrieval contract surfaced
 * here ({@code search(collection, query, maxResults, minScore)} →
 * ranked {@code RetrievedChunk}s) is identical regardless of backend.</p>
 */
@Component
public class KnowledgeBaseSeeder {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeBaseSeeder.class);

    /** Collection name shared with {@code support-skill/SKILL.md}. */
    public static final String COLLECTION = "support-faq";

    private final VectorStorePort vectorStore;

    public KnowledgeBaseSeeder(VectorStorePort vectorStore) {
        this.vectorStore = vectorStore;
    }

    @PostConstruct
    public void seed() {
        if (!(vectorStore instanceof InMemoryVectorStore inMemory)) {
            log.warn("[KnowledgeBaseSeeder] VectorStorePort is {}, not InMemoryVectorStore — "
                    + "skipping seed (production apps would use a proper ingest pipeline)",
                    vectorStore.getClass().getSimpleName());
            return;
        }
        inMemory.addChunk(COLLECTION,
                "Refunds are processed within 7 business days of approval. "
                + "A refund can be issued only on the original payment method.",
                "refund-policy.md");
        inMemory.addChunk(COLLECTION,
                "Critical incidents have a 1-hour first-response SLA and a 24-hour resolution SLA. "
                + "Standard incidents have a 1-business-day first-response SLA.",
                "sla.md");
        inMemory.addChunk(COLLECTION,
                "Password resets require a verified email address. The reset link expires after 30 minutes.",
                "auth-policy.md");
        inMemory.addChunk(COLLECTION,
                "Maintenance windows are announced 7 days in advance via the status page.",
                "maintenance-policy.md");
        log.info("[KnowledgeBaseSeeder] Seeded 4 chunks into '{}' collection", COLLECTION);
    }
}
