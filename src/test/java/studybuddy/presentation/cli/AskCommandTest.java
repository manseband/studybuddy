package studybuddy.presentation.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import studybuddy.ai.DocumentParser;
import studybuddy.ai.FakeLLMClient;
import studybuddy.ai.KeywordRetriever;
import studybuddy.ai.ResponseParser;
import studybuddy.application.ApplicationFactory;
import studybuddy.application.EventBus;
import studybuddy.application.LearningContentService;
import studybuddy.application.MaterialService;
import studybuddy.application.StudyAgentFacade;
import studybuddy.config.AppConfig;
import studybuddy.persistence.CourseRepository;
import studybuddy.persistence.Database;
import studybuddy.persistence.MaterialRepository;
import studybuddy.persistence.QaHistoryRepository;
import studybuddy.persistence.ConceptRepository;

class AskCommandTest {

    @TempDir
    Path dir;

    private Database db;
    private FakeLLMClient llm;
    private String output;

    @BeforeEach
    void setUp() throws IOException {
        db = Database.open(dir.resolve("ask.db"));
        llm = new FakeLLMClient();
        StudyAgentFacade facade = facade();
        long courseId = facade.createCourse("EECS3311", "", null).getId();
        facade.uploadMaterial(courseId, Files.writeString(dir.resolve("patterns.txt"),
                "The Facade pattern gives one simple interface to a whole subsystem."));
    }

    @AfterEach
    void tearDown() {
        db.close();
    }

    /** Facade over the shared test database with the scripted LLM (closing it leaves the database open). */
    private StudyAgentFacade facade() {
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

    private int run(Supplier<StudyAgentFacade> factory, String... args) {
        StringWriter buffer = new StringWriter();
        int code = new StudyAgentCli(factory).execute(args, new PrintWriter(buffer));
        output = buffer.toString().strip();
        return code;
    }

    @Test
    void printsAnswerWithSources() {
        llm.enqueueText("{\"answer\": \"A Facade offers one simple interface [1].\", \"citations\": [1], \"covered\": true}");

        assertEquals(0, run(this::facade, "ask", "--course", "EECS3311", "What", "does", "a", "Facade", "do?"));

        assertTrue(output.startsWith("A Facade offers one simple interface [1]."), output);
        assertTrue(output.contains("Sources:\n [1] patterns.txt, p. 1: \"The Facade pattern"), output);
    }

    @Test
    void jsonIncludesCitations() throws IOException {
        llm.enqueueText("{\"answer\": \"One interface [1].\", \"citations\": [1], \"covered\": true}");

        assertEquals(0, run(this::facade, "ask", "--course", "EECS3311", "--json", "Facade interface?"));

        Map<String, Object> json = new ObjectMapper().readValue(output, new TypeReference<>() { });
        assertEquals(true, json.get("grounded"));
        List<?> citations = (List<?>) json.get("citations");
        assertEquals("patterns.txt", ((Map<?, ?>) citations.get(0)).get("material"));
    }

    @Test
    void notCoveredSuggestsGeneralFlag() {
        assertEquals(0, run(this::facade, "ask", "--course", "EECS3311", "What is photosynthesis?"));
        assertTrue(output.contains("--general"));
    }

    @Test
    void withoutKeyExplainsHowToAddOneAndShowsExcerpts() {
        db.close();
        AppConfig noKey = AppConfig.load(dir.resolve("no.env"),
                Map.of("STUDYBUDDY_DB_PATH", dir.resolve("ask.db").toString()));

        assertEquals(1, run(() -> ApplicationFactory.create(noKey), "ask", "--course", "EECS3311", "Facade interface?"));

        assertTrue(output.contains("GEMINI_API_KEY"), output);
        assertTrue(output.contains("Relevant excerpts from your materials:"), output);
        db = Database.inMemory();
    }
}
