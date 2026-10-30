package studybuddy.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import studybuddy.ai.DocumentParser;
import studybuddy.ai.FakeLLMClient;
import studybuddy.ai.KeywordRetriever;
import studybuddy.ai.LLMException;
import studybuddy.ai.NoKeyLLMClient;
import studybuddy.ai.ResponseParser;
import studybuddy.domain.Answer;
import studybuddy.domain.Citation;
import studybuddy.persistence.CourseRepository;
import studybuddy.persistence.Database;
import studybuddy.persistence.MaterialRepository;
import studybuddy.persistence.QaHistoryRepository;
import studybuddy.persistence.ConceptRepository;

class LearningContentServiceTest {

    @TempDir
    Path dir;

    private Database db;
    private FakeLLMClient llm;
    private LearningContentService service;
    private MaterialService materials;
    private long courseId;

    @BeforeEach
    void setUp() throws IOException {
        db = Database.inMemory();
        llm = new FakeLLMClient();
        KeywordRetriever retriever = new KeywordRetriever();
        MaterialRepository materialRepo = new MaterialRepository(db);
        EventBus bus = new EventBus();
        materials = new MaterialService(new CourseRepository(db), materialRepo, new DocumentParser(), retriever, bus,
                1_000_000, 12, 2);
        service = new LearningContentService(llm, retriever, new ResponseParser(), materialRepo,
                new QaHistoryRepository(db), new ConceptRepository(db), bus, 6, 6000);
        courseId = materials.createCourse("EECS3311", "", null).getId();
        // two pages -> two chunks: Facade on page 1, Adapter on page 2
        materials.uploadMaterial(courseId, Files.writeString(dir.resolve("patterns.txt"),
                "The Facade pattern gives one simple interface to a whole subsystem.\f"
                + "The Adapter pattern converts the interface of a class into another one."));
    }

    @AfterEach
    void tearDown() {
        db.close();
    }

    @Test
    void answersWithValidatedCitationsAndStoresHistory() {
        llm.enqueueText("{\"answer\": \"Adapter converts interfaces [1]; Facade simplifies a subsystem [2] [7].\","
                + " \"citations\": [1, 2, 7], \"covered\": true}");

        Answer answer = service.answerQuestion(courseId, "How is Adapter different from Facade interface?");

        assertTrue(answer.grounded());
        assertTrue(answer.partiallyUnverified(), "citation [7] was not provided and is removed");
        assertEquals(2, answer.citations().size());
        Citation first = answer.citations().get(0);
        assertEquals("patterns.txt", first.materialTitle());
        assertTrue(first.page() >= 1);
        String prompt = llm.lastRequest().lastUserText();
        assertTrue(prompt.contains("[1] (page"));
        assertTrue(prompt.endsWith("Question: How is Adapter different from Facade interface?"));
        assertEquals(1, service.getQuestionHistory(courseId).size());
    }

    @Test
    void unrelatedQuestionIsNotCoveredWithoutCallingTheModel() {
        Answer answer = service.answerQuestion(courseId, "What is photosynthesis?");

        assertFalse(answer.grounded());
        assertFalse(answer.generalKnowledge());
        assertEquals(LearningContentService.NOT_COVERED_TEXT, answer.text());
        assertTrue(llm.getRequests().isEmpty());
    }

    @Test
    void generalKnowledgeAnswerIsLabelled() {
        llm.enqueueText("{\"answer\": \"Plants turn light into chemical energy.\"}");

        Answer answer = service.answerQuestion(courseId, "What is photosynthesis?", true);

        assertTrue(answer.generalKnowledge());
        assertFalse(answer.grounded());
        assertEquals("Plants turn light into chemical energy.", answer.text());
    }

    @Test
    void modelSayingNotCoveredIsRespected() {
        llm.enqueueText("{\"answer\": \"\", \"citations\": [], \"covered\": false}");

        Answer answer = service.answerQuestion(courseId, "Who invented the Facade pattern?");

        assertEquals(LearningContentService.NOT_COVERED_TEXT, answer.text());
    }

    @Test
    void failureStillReturnsTheRetrievedExcerpts() {
        llm.enqueueFailure(new LLMException("timeout", true));

        ContentGenerationException e = assertThrows(ContentGenerationException.class,
                () -> service.answerQuestion(courseId, "Explain the Facade pattern"));

        assertFalse(e.getExcerpts().isEmpty());
        assertTrue(e.getExcerpts().get(0).excerpt().contains("Facade"));
        assertTrue(service.getQuestionHistory(courseId).isEmpty());
    }

    @Test
    void invalidQuestionsAndEmptyCoursesAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> service.answerQuestion(courseId, "  "));
        assertThrows(IllegalArgumentException.class, () -> service.answerQuestion(courseId, "x".repeat(1001)));
        long empty = materials.createCourse("Empty", "", null).getId();
        assertThrows(IllegalStateException.class, () -> service.answerQuestion(empty, "Anything?"));
    }

    @Test
    void missingKeyExplainsHowToAddOne() {
        MaterialRepository materialRepo = new MaterialRepository(db);
        KeywordRetriever retriever = new KeywordRetriever();
        retriever.index(materialRepo.findChunksByCourse(courseId));
        LearningContentService noKey = new LearningContentService(new NoKeyLLMClient(), retriever,
                new ResponseParser(), materialRepo, new QaHistoryRepository(db),
                new ConceptRepository(db), new EventBus(), 6, 6000);

        ContentGenerationException e = assertThrows(ContentGenerationException.class,
                () -> noKey.answerQuestion(courseId, "Explain the Facade pattern"));

        assertTrue(e.getMessage().contains("GEMINI_API_KEY"));
        assertFalse(e.getExcerpts().isEmpty(), "keyword search still finds the excerpts without a key");
    }
}
