package studybuddy.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import studybuddy.ai.DocumentParser;
import studybuddy.ai.FakeLLMClient;
import studybuddy.ai.KeywordRetriever;
import studybuddy.ai.LLMException;
import studybuddy.ai.ResponseParser;
import studybuddy.domain.Difficulty;
import studybuddy.domain.Explanation;
import studybuddy.domain.GradeResult;
import studybuddy.domain.Material;
import studybuddy.domain.MultipleChoiceQuestion;
import studybuddy.domain.Question;
import studybuddy.domain.Quiz;
import studybuddy.domain.QuizAttempt;
import studybuddy.domain.TrueFalseQuestion;
import studybuddy.persistence.AttemptRepository;
import studybuddy.persistence.ConceptRepository;
import studybuddy.persistence.CourseRepository;
import studybuddy.persistence.Database;
import studybuddy.persistence.ExplanationRepository;
import studybuddy.persistence.MaterialRepository;
import studybuddy.persistence.QaHistoryRepository;
import studybuddy.persistence.QuizRepository;

/** F08 with a scripted model. Each page of the material is its own chunk. */
class ExplanationTest {

    private static final String REPLY = "{\"explanation\": \"A Facade offers one interface.\", "
            + "\"misconception\": \"You mixed it up with Adapter.\", \"excerpt\": 1}";

    @TempDir
    Path dir;

    private Database db;
    private FakeLLMClient llm;
    private LearningContentService service;
    private MaterialService materials;
    private QuizRepository quizzes;
    private AttemptRepository attempts;
    private long courseId;
    private Material material;

    @BeforeEach
    void setUp() throws IOException {
        db = Database.inMemory();
        llm = new FakeLLMClient();
        KeywordRetriever retriever = new KeywordRetriever();
        MaterialRepository materialRepo = new MaterialRepository(db);
        materials = new MaterialService(new CourseRepository(db), materialRepo, new DocumentParser(), retriever,
                new EventBus(), 1_000_000, 10, 0);
        courseId = materials.createCourse("EECS3311", "").getId();
        String facadePage = "Facade pattern gives one simple interface to a subsystem here.";
        String adapterPage = "Adapter pattern converts an interface for a client that expects another.";
        String observerPage = "Observer pattern lets subjects notify registered listeners about every change.";
        material = materials.uploadMaterial(courseId, Files.writeString(dir.resolve("notes.txt"),
                facadePage + "\f" + adapterPage + "\f" + observerPage));
        quizzes = new QuizRepository(db);
        attempts = new AttemptRepository(db);
        service = new LearningContentService(llm, retriever, new ResponseParser(), materialRepo,
                new QaHistoryRepository(db), new ExplanationRepository(db), new ConceptRepository(db), new EventBus(),
                6, 6000);
    }

    @AfterEach
    void tearDown() {
        db.close();
    }

    /** A saved multiple-choice question from the given chunk, in a saved attempt; returns the attempt id. */
    private long savedAttempt(Question question) {
        Quiz quiz = new Quiz(0, courseId, LocalDateTime.now(), Difficulty.EASY);
        quiz.addQuestion(question);
        quizzes.save(quiz);
        QuizAttempt attempt = QuizAttempt.start(quiz);
        attempt.submit();
        attempt.recordResult(GradeResult.byRule(question.getId(), false, ""));
        return attempts.save(attempt).getId();
    }

    private MultipleChoiceQuestion question() {
        return new MultipleChoiceQuestion(0, "What does a Facade pattern offer?", Difficulty.EASY, 1, null,
                material.getChunks().get(0).getId(), List.of("One interface", "Many listeners", "A builder", "A command"), 0);
    }

    private String prompt() {
        return llm.lastRequest().systemText() + "\n" + llm.lastRequest().messages().get(0).text();
    }

    @Test
    void explainsAWrongAnswerFromTheSourceAndCitesIt() {
        MultipleChoiceQuestion question = question();
        long attemptId = savedAttempt(question);
        llm.enqueueText(REPLY);

        Explanation explanation = service.explainAnswer(attemptId, question, "B", false);

        assertEquals("A Facade offers one interface.", explanation.text());
        assertEquals("You mixed it up with Adapter.", explanation.misconception());
        assertTrue(explanation.sourceAvailable());
        assertEquals("notes.txt", explanation.citation().materialTitle());
        assertEquals(1, explanation.citation().page());
        assertTrue(explanation.citation().excerpt().contains("one simple interface"));
        assertTrue(prompt().contains("[1] (page 1)"), prompt());
        assertTrue(prompt().contains("B) Many listeners"), prompt());
        assertTrue(prompt().contains("Correct answer: A) One interface"), prompt());
        assertTrue(prompt().contains("Student's answer: B"), prompt());
        assertTrue(prompt().contains("answered incorrectly"), prompt());
    }

    @Test
    void theSourceComesFirstFollowedByAtMostTwoRelatedExcerpts() {
        TrueFalseQuestion question = new TrueFalseQuestion(0, "The pattern interface subsystem listeners client",
                Difficulty.EASY, 2, null, material.getChunks().get(1).getId(), true);
        long attemptId = savedAttempt(question);
        llm.enqueueText(REPLY.replace("\"excerpt\": 1", "\"excerpt\": 3"));

        Explanation explanation = service.explainAnswer(attemptId, question, "false", false);

        String text = llm.lastRequest().messages().get(0).text();
        assertTrue(text.indexOf("Adapter pattern") < text.indexOf("Facade pattern"), text);
        assertTrue(text.contains("[3]"), text);
        assertFalse(text.contains("[4]"), text);
        assertEquals(3, explanation.citation().excerptNumber());
    }

    @Test
    void anInvalidCitationFallsBackToTheSource() {
        MultipleChoiceQuestion question = question();
        long attemptId = savedAttempt(question);
        llm.enqueueText(REPLY.replace("\"excerpt\": 1", "\"excerpt\": 9"));

        Explanation explanation = service.explainAnswer(attemptId, question, "B", false);

        assertEquals(material.getChunks().get(0).getId(), explanation.citation().chunkId());
    }

    @Test
    void aCorrectAnswerGetsNoMisconception() {
        MultipleChoiceQuestion question = question();
        long attemptId = savedAttempt(question);
        llm.enqueueText(REPLY);

        Explanation explanation = service.explainAnswer(attemptId, question, "A", true);

        assertNull(explanation.misconception());
        assertTrue(prompt().contains("reinforce the reasoning"), prompt());
    }

    @Test
    void anUnansweredQuestionGetsNoMisconception() {
        MultipleChoiceQuestion question = question();
        long attemptId = savedAttempt(question);
        llm.enqueueText(REPLY);

        assertNull(service.explainAnswer(attemptId, question, "", false).misconception());
        assertTrue(prompt().contains("Student's answer: (none)"), prompt());
    }

    @Test
    void aSecondRequestUsesTheCacheEvenAfterARestart() {
        MultipleChoiceQuestion question = question();
        long attemptId = savedAttempt(question);
        llm.enqueueText(REPLY);
        Explanation first = service.explainAnswer(attemptId, question, "B", false);

        Explanation second = service.explainAnswer(attemptId, question, "B", false);
        LearningContentService restarted = new LearningContentService(llm, new KeywordRetriever(), new ResponseParser(),
                new MaterialRepository(db), new QaHistoryRepository(db), new ExplanationRepository(db),
                new ConceptRepository(db), new EventBus(), 6, 6000);
        Explanation third = restarted.explainAnswer(attemptId, question, "B", false);

        assertEquals(first, second);
        assertEquals(first, third);
        assertEquals(1, llm.getRequests().size());
    }

    @Test
    void clearingTheCacheAsksAgain() {
        MultipleChoiceQuestion question = question();
        long attemptId = savedAttempt(question);
        llm.enqueueText(REPLY);
        service.explainAnswer(attemptId, question, "B", false);

        service.clearExplanation(attemptId, question.getId());
        llm.enqueueText(REPLY);
        service.explainAnswer(attemptId, question, "B", true);

        assertEquals(2, llm.getRequests().size());
    }

    @Test
    void removedSourceMaterialIsLabelledAndUsesTheAnswerKeyOnly() {
        MultipleChoiceQuestion question = question();
        long attemptId = savedAttempt(question);
        materials.removeMaterial(material.getId());
        Question stored = quizzes.findById(quizzes.findByCourse(courseId).get(0).getId()).orElseThrow().getQuestions().get(0);
        assertNull(stored.getSourceChunkId());
        llm.enqueueText("{\"explanation\": \"From the key.\", \"misconception\": null, \"excerpt\": null}");

        Explanation explanation = service.explainAnswer(attemptId, stored, "B", false);

        assertFalse(explanation.sourceAvailable());
        assertNull(explanation.citation());
        assertTrue(prompt().contains("no longer available"), prompt());
        assertFalse(prompt().contains("[1]"), prompt());
        assertEquals(explanation, service.explainAnswer(attemptId, stored, "B", false));
    }

    @Test
    void modelFailureOrAnEmptyReplyIsAnErrorAndIsNotCached() {
        MultipleChoiceQuestion question = question();
        long attemptId = savedAttempt(question);
        llm.enqueueFailure(new LLMException("rate limited", true));
        assertThrows(ContentGenerationException.class, () -> service.explainAnswer(attemptId, question, "B", false));

        llm.enqueueText("{\"explanation\": \" \"}");
        assertThrows(ContentGenerationException.class, () -> service.explainAnswer(attemptId, question, "B", false));

        llm.enqueueText(REPLY);
        assertEquals("A Facade offers one interface.", service.explainAnswer(attemptId, question, "B", false).text());
    }
}
