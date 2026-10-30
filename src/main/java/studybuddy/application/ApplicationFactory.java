package studybuddy.application;

import java.time.Duration;

import studybuddy.ai.DocumentParser;
import studybuddy.ai.EmbeddingRetriever;
import studybuddy.ai.KeywordRetriever;
import studybuddy.ai.RetrievalStrategy;
import studybuddy.config.AppConfig;
import studybuddy.persistence.CourseRepository;
import studybuddy.persistence.Database;
import studybuddy.persistence.MaterialRepository;

/**
 * Builds the object graph behind the {@link StudyAgentFacade} from the configuration.
 * This is the only place that chooses concrete strategies: the Gemini embedding retriever when an
 * API key is configured, the keyword retriever otherwise.
 */
public final class ApplicationFactory {

    private ApplicationFactory() {
    }

    public static StudyAgentFacade create(AppConfig config) {
        Database db = Database.open(config.databasePath());
        try {
            EventBus eventBus = new EventBus();
            RetrievalStrategy retriever = createRetriever(config);
            MaterialService materialService = new MaterialService(new CourseRepository(db), new MaterialRepository(db),
                    new DocumentParser(), retriever, eventBus, config.maxFileBytes(), config.chunkWords(),
                    config.chunkOverlap());
            materialService.loadIndex();
            return new StudyAgentFacade(materialService, eventBus, config.hasApiKey(), db);
        } catch (RuntimeException e) {
            db.close();
            throw e;
        }
    }

    static RetrievalStrategy createRetriever(AppConfig config) {
        return config.apiKey()
                .<RetrievalStrategy>map(key -> EmbeddingRetriever.create(key, config.embeddingModel(),
                        Duration.ofSeconds(config.llmTimeoutSeconds()), config.minSimilarity()))
                .orElseGet(KeywordRetriever::new);
    }
}
