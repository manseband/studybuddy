package studybuddy.application;

import java.time.Duration;
import java.util.Map;

import studybuddy.ai.DocumentParser;
import studybuddy.ai.EmbeddingRetriever;
import studybuddy.ai.GeminiClient;
import studybuddy.ai.KeywordRetriever;
import studybuddy.ai.LLMClient;
import studybuddy.ai.NoKeyLLMClient;
import studybuddy.ai.ResponseParser;
import studybuddy.ai.RetrievalStrategy;
import studybuddy.config.AppConfig;
import studybuddy.domain.QuestionType;
import studybuddy.grading.GradingStrategy;
import studybuddy.grading.LLMRubricGrader;
import studybuddy.grading.RuleBasedGrader;
import studybuddy.persistence.AttemptRepository;
import studybuddy.persistence.ConceptRepository;
import studybuddy.persistence.CourseRepository;
import studybuddy.persistence.Database;
import studybuddy.persistence.MaterialRepository;
import studybuddy.persistence.QaHistoryRepository;
import studybuddy.persistence.QuizRepository;

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
            LLMClient routineClient = createRoutineClient(config);
            ResponseParser responseParser = new ResponseParser();
            ConceptRepository conceptRepo = new ConceptRepository(db);
            LearningContentService contentService = new LearningContentService(routineClient, retriever,
                    responseParser, materialRepo, new QaHistoryRepository(db), conceptRepo, eventBus,
                    config.retrievalTopK(), config.promptWordBudget());
            QuizService quizService = new QuizService(routineClient, retriever, responseParser, new QuestionFactory(),
                    createGraders(routineClient, responseParser), new QuizRepository(db), new AttemptRepository(db),
                    materialRepo, conceptRepo, eventBus, config.retrievalTopK(), config.promptWordBudget());
            return new StudyAgentFacade(materialService, contentService, quizService, eventBus, config.hasApiKey(), db);
        } catch (RuntimeException e) {
            db.close();
            throw e;
        }
    }

    /** F07: objective questions are graded by rules, short answers by the model. */
    public static Map<QuestionType, GradingStrategy> createGraders(LLMClient llm, ResponseParser responseParser) {
        GradingStrategy rules = new RuleBasedGrader();
        return Map.of(QuestionType.MULTIPLE_CHOICE, rules, QuestionType.TRUE_FALSE, rules,
                QuestionType.SHORT_ANSWER, new LLMRubricGrader(llm, responseParser));
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
