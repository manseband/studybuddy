package studybuddy.application;

import java.time.Duration;

import studybuddy.ai.DocumentParser;
import studybuddy.ai.EmbeddingRetriever;
import studybuddy.ai.GeminiClient;
import studybuddy.ai.KeywordRetriever;
import studybuddy.ai.LLMClient;
import studybuddy.ai.NoKeyLLMClient;
import studybuddy.ai.ResponseParser;
import studybuddy.ai.RetrievalStrategy;
import studybuddy.config.AppConfig;
import studybuddy.persistence.ConceptRepository;
import studybuddy.persistence.CourseRepository;
import studybuddy.persistence.Database;
import studybuddy.persistence.MaterialRepository;
import studybuddy.persistence.QaHistoryRepository;

/**
 * Builds the object graph behind the {@link StudyAgentFacade} from the configuration.
 * This is the only place that chooses concrete strategies: with an API key, the Gemini chat client and
 * embedding retriever; without one, NoKeyLLMClient (explains how to add a key) and the keyword retriever.
 */
public final class ApplicationFactory {

    private ApplicationFactory() {
    }

    public static StudyAgentFacade create(AppConfig config) {
        Database db = Database.open(config.databasePath());
        try {
            EventBus eventBus = new EventBus();
            RetrievalStrategy retriever = createRetriever(config);
            MaterialRepository materialRepo = new MaterialRepository(db);
            MaterialService materialService = new MaterialService(new CourseRepository(db), materialRepo,
                    new DocumentParser(), retriever, eventBus, config.maxFileBytes(), config.chunkWords(),
                    config.chunkOverlap());
            materialService.loadIndex();
            LearningContentService contentService = new LearningContentService(createRoutineClient(config),
                    retriever, new ResponseParser(), materialRepo, new QaHistoryRepository(db),
                    new ConceptRepository(db), eventBus, config.retrievalTopK(),
                    config.promptWordBudget());
            return new StudyAgentFacade(materialService, contentService, eventBus, config.hasApiKey(), db);
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

    /** Client for routine generation and grading (Flash-Lite in the default configuration). */
    static LLMClient createRoutineClient(AppConfig config) {
        return config.apiKey()
                .<LLMClient>map(key -> GeminiClient.create(key, config.routineModel(),
                        Duration.ofSeconds(config.llmTimeoutSeconds()), config.llmMaxRetries()))
                .orElseGet(NoKeyLLMClient::new);
    }
}
