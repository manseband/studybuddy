package studybuddy.presentation.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import studybuddy.ai.LLMException;
import studybuddy.application.StudyAgentFacade;
import studybuddy.grading.LLMRubricGrader;

class QuizCommandTest {

    private static final String THREE_QUESTIONS = """
            {"questions": [
              {"type": "mcq", "stem": "Which hides a subsystem?", "options": ["Facade", "Observer", "Builder", "Command"],
               "correctIndex": 0, "excerpt": 1, "page": 1, "concept": null},
              {"type": "tf", "stem": "An Adapter converts an interface.", "answer": true, "excerpt": 1, "page": 1, "concept": null},
              {"type": "short", "stem": "What does a Facade offer?", "referenceAnswer": "One simple interface.",
               "rubric": "Mentions a simple interface.", "excerpt": 1, "page": 1, "concept": null}]}""";

    @TempDir
    Path dir;

    private CliTestApp app;
    private final ObjectMapper mapper = new ObjectMapper();

    @BeforeAll
    static void silenceExpectedWarnings() {
        Logger.getLogger(LLMRubricGrader.class.getName()).setLevel(Level.OFF);
    }

    @BeforeEach
    void setUp() throws IOException {
        app = new CliTestApp(dir.resolve("quiz.db"));
        StudyAgentFacade facade = app.facade();
        long courseId = facade.createCourse("EECS3311", "", null).getId();
        facade.uploadMaterial(courseId, Files.writeString(dir.resolve("patterns.txt"),
                "The Facade pattern gives one simple interface to a whole subsystem. ".repeat(20)));
    }

    @AfterEach
    void tearDown() {
        app.close();
    }

    private long generateQuiz() throws IOException {
        app.llm.enqueueText(THREE_QUESTIONS);
        assertEquals(0, app.run("quiz", "generate", "--course", "EECS3311", "--count", "3", "--json"), app.output);
        return mapper.readTree(app.output).get("id").asLong();
    }

    private int runWithInput(String input, String... args) {
        InputStream original = System.in;
        System.setIn(new ByteArrayInputStream(input.getBytes(StandardCharsets.UTF_8)));
        try {
            return app.run(args);
        } finally {
            System.setIn(original);
        }
    }

    @Test
    void generatePrintsTheQuizId() {
        app.llm.enqueueText(THREE_QUESTIONS);

        assertEquals(0, app.run("quiz", "generate", "--course", "EECS3311", "--count", "3"));

        assertTrue(app.output.contains("Created quiz #1 with 3 question(s)"), app.output);
        assertFalse(app.output.contains("Note:"), app.output);
    }

    @Test
    void generateNoticesAShorterQuiz() {
        app.llm.enqueueText(THREE_QUESTIONS);
        app.llm.enqueueText("{\"questions\": []}");
        app.llm.enqueueText("{\"questions\": []}");

        assertEquals(0, app.run("quiz", "generate", "--course", "EECS3311", "--count", "5"));

        assertTrue(app.output.contains("only 3 of the 5"), app.output);
    }

    @Test
    void generateJsonDoesNotRevealAnswers() throws IOException {
        generateQuiz();

        JsonNode json = mapper.readTree(app.output);

        assertEquals(3, json.get("questions").size());
        assertEquals("mcq", json.get("questions").get(0).get("type").asText());
        assertEquals(3, json.get("requested").asInt());
        assertFalse(app.output.contains("correctIndex"));
        assertFalse(app.output.contains("referenceAnswer"));
        assertFalse(app.output.contains("rubric"));
    }

    @Test
    void generateRejectsBadOptions() {
        assertEquals(1, app.run("quiz", "generate", "--course", "EECS3311", "--count", "99"));
        assertTrue(app.output.contains("between 1 and 30"), app.output);
        assertEquals(1, app.run("quiz", "generate", "--course", "EECS3311", "--types", "essay"));
        assertTrue(app.output.contains("essay"), app.output);
        assertEquals(1, app.run("quiz", "generate", "--course", "EECS3311", "--difficulty", "brutal"));
        assertTrue(app.output.contains("brutal"), app.output);
    }

    @Test
    void takeAsksEachQuestionThenPrintsResults() throws IOException {
        long quizId = generateQuiz();
        app.llm.enqueueText("{\"score\": 1.0, \"feedback\": \"Exactly right.\", \"missingPoints\": []}");

        assertEquals(0, runWithInput("A\nf\nA simple interface\n", "quiz", "take", String.valueOf(quizId)), app.output);

        assertTrue(app.output.contains("A) Facade"), app.output);
        assertTrue(app.output.contains("Answer (t/f"), app.output);
        assertTrue(app.output.contains("2.0 / 3 (67%)"), app.output);
        assertTrue(app.output.contains("[RIGHT 1.00] Which hides"), app.output);
        assertTrue(app.output.contains("[WRONG 0.00] An Adapter"), app.output);
        assertTrue(app.output.contains("Exactly right."), app.output);
    }

    @Test
    void takeWithJsonPrintsOnlyJson() throws IOException {
        long quizId = generateQuiz();

        assertEquals(0, runWithInput("", "quiz", "take", String.valueOf(quizId), "--json"), app.output);

        JsonNode json = mapper.readTree(app.output);
        assertEquals("GRADED", json.get("status").asText());
        assertEquals(0.0, json.get("totalScore").asDouble());
    }

    @Test
    void gradeReadsAnswersFromAFile() throws IOException {
        long quizId = generateQuiz();
        JsonNode questions = mapper.readTree(app.output).get("questions");
        Path answers = Files.writeString(dir.resolve("answers.json"), "{\"quizId\": " + quizId + ", \"answers\": {\""
                + questions.get(0).get("id").asLong() + "\": \"A\", \""
                + questions.get(1).get("id").asLong() + "\": \"true\"}}");

        assertEquals(0, app.run("quiz", "grade", "--attempt", answers.toString(), "--json"), app.output);

        JsonNode json = mapper.readTree(app.output);
        assertEquals(2.0, json.get("totalScore").asDouble());
        assertEquals("rule", json.get("results").get(0).get("gradedBy").asText());
        assertEquals("A) Facade", json.get("results").get(0).get("correctAnswer").asText());
    }

    @Test
    void gradeRejectsBadFiles() throws IOException {
        long quizId = generateQuiz();
        Path noQuiz = Files.writeString(dir.resolve("bad.json"), "{\"answers\": {}}");
        assertEquals(1, app.run("quiz", "grade", "--attempt", noQuiz.toString()));
        assertTrue(app.output.contains("quizId"), app.output);

        Path otherQuestion = Files.writeString(dir.resolve("other.json"),
                "{\"quizId\": " + quizId + ", \"answers\": {\"99999\": \"A\"}}");
        assertEquals(1, app.run("quiz", "grade", "--attempt", otherQuestion.toString()));
        assertTrue(app.output.contains("not part of quiz"), app.output);
    }

    @Test
    void pendingAnswerCanBeRegradedAndDisputed() throws IOException {
        long quizId = generateQuiz();
        app.llm.enqueueFailure(new LLMException("rate limited", true));
        assertEquals(0, runWithInput("A\nt\nSome text\n", "quiz", "take", String.valueOf(quizId), "--json"), app.output);
        JsonNode taken = mapper.readTree(app.output);
        assertEquals("PENDING_GRADING", taken.get("status").asText());
        String attemptId = taken.get("attemptId").asText();
        String shortId = taken.get("results").get(2).get("questionId").asText();

        app.run("quiz", "results", attemptId);
        assertTrue(app.output.contains("[PENDING"), app.output);
        assertTrue(app.output.contains("quiz regrade " + attemptId), app.output);

        app.llm.enqueueText("{\"score\": 0.5, \"feedback\": \"Partly.\"}");
        assertEquals(0, app.run("quiz", "regrade", attemptId, "--json"), app.output);
        assertEquals("GRADED", mapper.readTree(app.output).get("status").asText());

        assertEquals(0, app.run("quiz", "dispute", attemptId, "--question", shortId, "--score", "1", "--json"), app.output);
        JsonNode disputed = mapper.readTree(app.output);
        assertEquals(3.0, disputed.get("totalScore").asDouble());
        assertTrue(disputed.get("results").get(2).get("overridden").asBoolean());

        assertEquals(1, app.run("quiz", "dispute", attemptId, "--question", shortId, "--score", "2"));
        assertTrue(app.output.contains("between 0 and 1"), app.output);
    }

    @Test
    void listAndShowDescribeQuizzes() throws IOException {
        assertEquals(0, app.run("quiz", "list", "--course", "EECS3311"));
        assertTrue(app.output.contains("No quizzes yet"), app.output);

        long quizId = generateQuiz();
        runWithInput("A\n\n\n", "quiz", "take", String.valueOf(quizId));

        assertEquals(0, app.run("quiz", "list", "--course", "EECS3311"));
        assertTrue(app.output.contains("3 question(s)  1 attempt(s)  latest 33%"), app.output);

        assertEquals(0, app.run("quiz", "show", String.valueOf(quizId)));
        assertTrue(app.output.contains("D) Command"), app.output);
    }

    @Test
    void unknownQuizIsAnError() {
        assertEquals(1, app.run("quiz", "show", "99"));
        assertTrue(app.output.contains("No quiz with id 99"), app.output);
    }
}
