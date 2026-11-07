package studybuddy.presentation.cli;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Path;

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
import studybuddy.persistence.ExplanationRepository;
import studybuddy.persistence.FlashcardRepository;
import studybuddy.persistence.MaterialRepository;
import studybuddy.persistence.PreferenceRepository;
import studybuddy.persistence.QaHistoryRepository;
import studybuddy.persistence.QuizRepository;

/**
 * Test helper: runs CLI commands against a facade backed by a real SQLite file and a scripted
 * {@link FakeLLMClient}, so command output can be checked without calling Gemini.
 */
class CliTestApp implements AutoCloseable {

    final FakeLLMClient llm = new FakeLLMClient();
    private final Database db;
    String output;

    CliTestApp(Path dbFile) {
        db = Database.open(dbFile);
    }

    /** A fresh facade over the shared database (closing it leaves the database open). */
    StudyAgentFacade facade() {
        KeywordRetriever retriever = new KeywordRetriever();
        MaterialRepository materialRepo = new MaterialRepository(db);
        EventBus bus = new EventBus();
        MaterialService materials = new MaterialService(new CourseRepository(db), materialRepo, new DocumentParser(),
                retriever, bus, 1_000_000, 400, 50);
        materials.loadIndex();
        ResponseParser parser = new ResponseParser();
        ConceptRepository conceptRepo = new ConceptRepository(db);
        LearningContentService content = new LearningContentService(llm, retriever, parser, materialRepo,
                new QaHistoryRepository(db), new ExplanationRepository(db), conceptRepo, bus, 6, 6000);
        QuizRepository quizRepo = new QuizRepository(db);
        AttemptRepository attemptRepo = new AttemptRepository(db);
        QuizService quizzes = new QuizService(llm, retriever, parser, new QuestionFactory(),
                ApplicationFactory.createGraders(llm, parser), quizRepo, attemptRepo,
                materialRepo, conceptRepo, bus, 6, 6000);
        FlashcardRepository cardRepo = new FlashcardRepository(db);
        MasteryService mastery = new MasteryService(attemptRepo, quizRepo, conceptRepo, cardRepo, llm, parser, 60);
        FlashcardService cards = new FlashcardService(llm, retriever, parser, cardRepo, materialRepo, conceptRepo, bus,
                6, 6000);
        AgentController agent = ApplicationFactory.createAgent(llm, materials, content, quizzes, mastery, cards,
                new PreferenceRepository(db), bus, 8);
        return new StudyAgentFacade(materials, content, quizzes, mastery, cards, agent, bus, true, () -> { });
    }

    /** Runs one command line; the output is kept in {@link #output}. */
    int run(String... args) {
        StringWriter buffer = new StringWriter();
        int code = new StudyAgentCli(this::facade).execute(args, new PrintWriter(buffer));
        output = buffer.toString().strip();
        return code;
    }

    @Override
    public void close() {
        db.close();
    }
}
