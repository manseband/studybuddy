package studybuddy.presentation.gui;

import studybuddy.agent.AgentController;
import studybuddy.ai.DocumentParser;
import studybuddy.ai.FakeLLMClient;
import studybuddy.ai.KeywordRetriever;
import studybuddy.ai.ResponseParser;
import studybuddy.application.ApplicationFactory;
import studybuddy.application.EventBus;
import studybuddy.application.FlashcardService;
import studybuddy.application.LearningContentService;
import studybuddy.application.MasteryService;
import studybuddy.application.MaterialService;
import studybuddy.application.QuestionFactory;
import studybuddy.application.QuizService;
import studybuddy.application.StudyAgentFacade;
import studybuddy.persistence.AttemptRepository;
import studybuddy.persistence.ConceptRepository;
import studybuddy.persistence.CourseRepository;
import studybuddy.persistence.Database;
import studybuddy.persistence.FlashcardRepository;
import studybuddy.persistence.MaterialRepository;
import studybuddy.persistence.PreferenceRepository;
import studybuddy.persistence.QaHistoryRepository;
import studybuddy.persistence.QuizRepository;

/** Test helper: a facade with a scripted model, for driving panels without a real API key. */
final class GuiTestFacade {

    private GuiTestFacade() {
    }

    static StudyAgentFacade create(Database db, FakeLLMClient llm) {
        KeywordRetriever retriever = new KeywordRetriever();
        MaterialRepository materials = new MaterialRepository(db);
        EventBus bus = new EventBus();
        ResponseParser parser = new ResponseParser();
        ConceptRepository concepts = new ConceptRepository(db);
        QuizRepository quizzes = new QuizRepository(db);
        AttemptRepository attempts = new AttemptRepository(db);
        FlashcardRepository cards = new FlashcardRepository(db);
        MaterialService materialService = new MaterialService(new CourseRepository(db), materials, new DocumentParser(),
                retriever, bus, 1_000_000, 400, 50);
        LearningContentService content = new LearningContentService(llm, retriever, parser, materials,
                new QaHistoryRepository(db), concepts, bus, 6, 6000);
        QuizService quizService = new QuizService(llm, retriever, parser, new QuestionFactory(),
                ApplicationFactory.createGraders(llm, parser), quizzes, attempts, materials, concepts, bus, 6, 6000);
        MasteryService mastery = new MasteryService(attempts, quizzes, concepts, cards, llm, parser, 60);
        FlashcardService flashcards = new FlashcardService(llm, retriever, parser, cards, materials, concepts, bus, 6,
                6000);
        AgentController agent = ApplicationFactory.createAgent(llm, materialService, content, quizService, mastery,
                flashcards, new PreferenceRepository(db), bus, 8);
        return new StudyAgentFacade(materialService, content, quizService, mastery, flashcards, agent, bus, true,
                () -> { });
    }
}
