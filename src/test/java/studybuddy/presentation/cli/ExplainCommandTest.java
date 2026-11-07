package studybuddy.presentation.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import studybuddy.ai.LLMException;
import studybuddy.application.StudyAgentFacade;

class ExplainCommandTest {

    private static final String QUIZ = """
            {"questions": [
              {"type": "mcq", "stem": "Which hides a subsystem?", "options": ["Facade", "Observer", "Builder", "Command"],
               "correctIndex": 0, "excerpt": 1, "page": 1, "concept": null},
              {"type": "short", "stem": "What does a Facade offer?", "referenceAnswer": "One simple interface.",
               "rubric": "Mentions a simple interface.", "excerpt": 1, "page": 1, "concept": null}]}""";
    private static final String EXPLANATION = "{\"explanation\": \"A Facade hides a subsystem.\", "
            + "\"misconception\": \"You confused it with Observer.\", \"excerpt\": 1}";

    @TempDir
    Path dir;

    private CliTestApp app;
    private final ObjectMapper mapper = new ObjectMapper();
    private String attemptId;
    private String mcqId;
    private String shortId;

    @BeforeEach
    void setUp() throws IOException {
        app = new CliTestApp(dir.resolve("explain.db"));
        StudyAgentFacade facade = app.facade();
        long courseId = facade.createCourse("EECS3311", "").getId();
        facade.uploadMaterial(courseId, Files.writeString(dir.resolve("patterns.txt"),
                "The Facade pattern gives one simple interface to a whole subsystem. ".repeat(20)));
    }

    @AfterEach
    void tearDown() {
        app.close();
    }

    /** Takes a quiz, answering the multiple-choice question wrongly; the short answer's grading can be scripted. */
    private void takeQuiz(Runnable scriptShortAnswerGrade) throws IOException {
        app.llm.enqueueText(QUIZ);
        assertEquals(0, app.run("quiz", "generate", "--course", "EECS3311", "--count", "2", "--types", "mcq,short",
                "--json"), app.output);
        JsonNode quiz = mapper.readTree(app.output);
        mcqId = quiz.get("questions").get(0).get("id").asText();
        shortId = quiz.get("questions").get(1).get("id").asText();
        Path answers = Files.writeString(dir.resolve("answers.json"), "{\"quizId\": " + quiz.get("id").asText()
                + ", \"answers\": {\"" + mcqId + "\": \"B\", \"" + shortId + "\": \"Some text\"}}");
        scriptShortAnswerGrade.run();
        assertEquals(0, app.run("quiz", "grade", "--attempt", answers.toString(), "--json"), app.output);
        attemptId = mapper.readTree(app.output).get("attemptId").asText();
    }

    private void takeQuiz() throws IOException {
        takeQuiz(() -> app.llm.enqueueText("{\"score\": 0.25, \"feedback\": \"Thin.\"}"));
    }

    @Test
    void printsTheExplanationMisconceptionAndSource() throws IOException {
        takeQuiz();
        app.llm.enqueueText(EXPLANATION);

        assertEquals(0, app.run("explain", "--attempt", attemptId, "--question", mcqId), app.output);

        assertTrue(app.output.contains("A Facade hides a subsystem."), app.output);
        assertTrue(app.output.contains("Likely misconception: You confused it with Observer."), app.output);
        assertTrue(app.output.contains("Source: patterns.txt, p. 1"), app.output);
    }

    @Test
    void jsonHasTheCitationAndTheSecondRequestUsesTheCache() throws IOException {
        takeQuiz();
        app.llm.enqueueText(EXPLANATION);
        assertEquals(0, app.run("explain", "--attempt", attemptId, "--question", mcqId, "--json"), app.output);
        int calls = app.llm.getRequests().size();

        assertEquals(0, app.run("explain", "--attempt", attemptId, "--question", mcqId, "--json"), app.output);

        assertEquals(calls, app.llm.getRequests().size());
        JsonNode json = mapper.readTree(app.output);
        assertEquals("A Facade hides a subsystem.", json.get("explanation").asText());
        assertTrue(json.get("sourceAvailable").asBoolean());
        assertEquals("patterns.txt", json.get("citation").get("material").asText());
    }

    @Test
    void modelFailureStillShowsTheCorrectAnswer() throws IOException {
        takeQuiz();
        app.llm.enqueueFailure(new LLMException("rate limited", true));

        assertEquals(1, app.run("explain", "--attempt", attemptId, "--question", mcqId));

        assertTrue(app.output.contains("rate limited"), app.output);
        assertTrue(app.output.contains("Correct answer: A) Facade"), app.output);
    }

    @Test
    void aPendingAnswerCannotBeExplainedYet() throws IOException {
        takeQuiz(() -> app.llm.enqueueFailure(new LLMException("rate limited", true)));

        assertEquals(1, app.run("explain", "--attempt", attemptId, "--question", shortId));

        assertTrue(app.output.contains("not been graded yet"), app.output);
    }

    @Test
    void aQuestionOutsideTheAttemptIsAnError() throws IOException {
        takeQuiz();

        assertEquals(1, app.run("explain", "--attempt", attemptId, "--question", "99999"));

        assertTrue(app.output.contains("not part of this attempt"), app.output);
    }

    @Test
    void disputingAGradeForgetsTheOldExplanation() throws IOException {
        takeQuiz();
        app.llm.enqueueText(EXPLANATION);
        assertEquals(0, app.run("explain", "--attempt", attemptId, "--question", shortId), app.output);
        assertTrue(app.output.contains("Likely misconception"), app.output);

        assertEquals(0, app.run("quiz", "dispute", attemptId, "--question", shortId, "--score", "1"), app.output);
        app.llm.enqueueText(EXPLANATION);
        assertEquals(0, app.run("explain", "--attempt", attemptId, "--question", shortId), app.output);

        assertFalse(app.output.contains("Likely misconception"), app.output);
        assertEquals(0, app.llm.remaining());
    }
}
