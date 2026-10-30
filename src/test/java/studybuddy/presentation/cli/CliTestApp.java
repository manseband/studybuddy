package studybuddy.presentation.cli;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Path;

import studybuddy.ai.DocumentParser;
import studybuddy.ai.FakeLLMClient;
import studybuddy.ai.KeywordRetriever;
import studybuddy.ai.ResponseParser;
import studybuddy.application.EventBus;
import studybuddy.application.LearningContentService;
import studybuddy.application.MaterialService;
import studybuddy.application.StudyAgentFacade;
import studybuddy.persistence.CourseRepository;
import studybuddy.persistence.Database;
import studybuddy.persistence.MaterialRepository;
import studybuddy.persistence.QaHistoryRepository;
import studybuddy.persistence.ConceptRepository;

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
        LearningContentService content = new LearningContentService(llm, retriever, new ResponseParser(), materialRepo,
                new QaHistoryRepository(db), new ConceptRepository(db), bus, 6, 6000);
        return new StudyAgentFacade(materials, content, bus, true, () -> { });
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
