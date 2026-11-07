package studybuddy.application;

import java.time.Duration;
import java.util.Map;

import studybuddy.agent.AgentController;
import studybuddy.agent.GenerateFlashcardsTool;
import studybuddy.agent.GenerateQuizTool;
import studybuddy.agent.GetWeakConceptsTool;
import studybuddy.agent.Planner;
import studybuddy.agent.SavePreferenceTool;
import studybuddy.agent.SearchMaterialsTool;
import studybuddy.agent.ToolManager;
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
import studybuddy.persistence.FlashcardRepository;
import studybuddy.persistence.MaterialRepository;
import studybuddy.persistence.PreferenceRepository;
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
            QuizRepository quizRepo = new QuizRepository(db);
            AttemptRepository attemptRepo = new AttemptRepository(db);
            QuizService quizService = new QuizService(routineClient, retriever, responseParser, new QuestionFactory(),
                    createGraders(routineClient, responseParser), quizRepo, attemptRepo,
                    materialRepo, conceptRepo, eventBus, config.retrievalTopK(), config.promptWordBudget());
            FlashcardRepository cardRepo = new FlashcardRepository(db);
            MasteryService masteryService = new MasteryService(attemptRepo, quizRepo, conceptRepo, cardRepo,
                    routineClient, responseParser, config.weakThreshold());
            FlashcardService flashcardService = new FlashcardService(routineClient, retriever, responseParser,
                    cardRepo, materialRepo, conceptRepo, eventBus, config.retrievalTopK(), config.promptWordBudget());
            AgentController agent = createAgent(createCoachClient(config), materialService, contentService, quizService,
                    masteryService, flashcardService, new PreferenceRepository(db), eventBus, config.agentMaxSteps());
            return new StudyAgentFacade(materialService, contentService, quizService, masteryService,
                    flashcardService, agent, eventBus, config.hasApiKey(), db);
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

    /** F12: the Study Coach and its tools, each of which wraps an existing service. */
    public static AgentController createAgent(LLMClient llm, MaterialService materialService,
                                              LearningContentService contentService, QuizService quizService,
                                              MasteryService masteryService, FlashcardService flashcardService,
                                              PreferenceRepository preferences, EventBus eventBus, int maxSteps) {
        ToolManager tools = new ToolManager();
        tools.register(new SearchMaterialsTool(contentService));
        tools.register(new GetWeakConceptsTool(masteryService));
        tools.register(new GenerateQuizTool(quizService));
        tools.register(new GenerateFlashcardsTool(flashcardService));
        tools.register(new SavePreferenceTool(preferences));
        return new AgentController(new Planner(llm, maxSteps), tools, materialService, contentService, preferences,
                eventBus);
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

    /** Client for the Study Coach's planning and tool calling. */
    static LLMClient createCoachClient(AppConfig config) {
        return config.apiKey()
                .<LLMClient>map(key -> GeminiClient.create(key, config.coachModel(),
                        Duration.ofSeconds(config.llmTimeoutSeconds()), config.llmMaxRetries()))
                .orElseGet(NoKeyLLMClient::new);
    }
}
