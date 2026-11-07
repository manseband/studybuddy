package studybuddy.presentation.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

class ProgressCommandTest {

    @TempDir
    Path dir;

    private CliTestApp app;
    private final ObjectMapper mapper = new ObjectMapper();

    @BeforeEach
    void setUp() throws IOException {
        app = new CliTestApp(dir.resolve("progress.db"));
        long courseId = app.facade().createCourse("EECS3311", "").getId();
        app.facade().uploadMaterial(courseId, Files.writeString(dir.resolve("patterns.txt"),
                "The Facade pattern gives one simple interface to a whole subsystem. ".repeat(20)));
    }

    @AfterEach
    void tearDown() {
        app.close();
    }

    private void takeAQuizAnsweringBothWrongOrRight() throws IOException {
        app.llm.enqueueText("""
                {"questions": [
                  {"type": "tf", "stem": "One", "answer": true, "excerpt": 1, "page": 1, "concept": null},
                  {"type": "tf", "stem": "Two", "answer": true, "excerpt": 1, "page": 1, "concept": null}]}""");
        assertEquals(0, app.run("quiz", "generate", "--course", "EECS3311", "--count", "2", "--types", "tf", "--json"),
                app.output);
        JsonNode quiz = mapper.readTree(app.output);
        Path answers = Files.writeString(dir.resolve("answers.json"), "{\"quizId\": " + quiz.get("id").asText()
                + ", \"answers\": {\"" + quiz.get("questions").get(0).get("id").asText() + "\": \"true\", \""
                + quiz.get("questions").get(1).get("id").asText() + "\": \"false\"}}");
        assertEquals(0, app.run("quiz", "grade", "--attempt", answers.toString()), app.output);
    }

    @Test
    void progressShowsTheAverageAndStreak() throws IOException {
        takeAQuizAnsweringBothWrongOrRight();

        assertEquals(0, app.run("progress", "--course", "EECS3311"), app.output);

        assertTrue(app.output.contains("Average score: 50% over 1 quiz"), app.output);
        assertTrue(app.output.contains("Current streak: 1 day"), app.output);
    }

    @Test
    void progressAsJson() throws IOException {
        assertEquals(0, app.run("progress", "--course", "EECS3311", "--json"), app.output);
        JsonNode empty = mapper.readTree(app.output);
        assertTrue(empty.get("averageScore").isNull());
        assertEquals(0, empty.get("currentStreak").asInt());

        takeAQuizAnsweringBothWrongOrRight();
        assertEquals(0, app.run("progress", "--course", "EECS3311", "--json"), app.output);

        JsonNode json = mapper.readTree(app.output);
        assertEquals(50.0, json.get("averageScore").asDouble());
        assertEquals(1, json.get("gradedQuizzes").asInt());
    }

    @Test
    void progressWithoutACourseIsAnError() {
        assertEquals(1, app.run("progress"));

        assertTrue(app.output.contains("--course"), app.output);
    }

    @Test
    void historyListsActivityAndFilters() throws IOException {
        takeAQuizAnsweringBothWrongOrRight();

        assertEquals(0, app.run("history", "--course", "EECS3311"), app.output);
        assertTrue(app.output.contains("Quiz taken"), app.output);
        assertTrue(app.output.contains("2 questions"), app.output);
        assertTrue(app.output.contains("50%"), app.output);
        assertTrue(app.output.contains("Material uploaded"), app.output);

        assertEquals(0, app.run("history", "--course", "EECS3311", "--type", "quiz"), app.output);
        assertFalse(app.output.contains("Material uploaded"), app.output);

        String tomorrow = LocalDate.now().plusDays(1).toString();
        assertEquals(0, app.run("history", "--course", "EECS3311", "--since", tomorrow), app.output);
        assertTrue(app.output.contains("No activity matches this filter"), app.output);
    }

    @Test
    void historyAsJson() throws IOException {
        assertEquals(0, app.run("history", "--course", "EECS3311", "--type", "material", "--json"), app.output);

        JsonNode row = mapper.readTree(app.output).get("history").get(0);
        assertEquals("material", row.get("type").asText());
        assertEquals("Material uploaded", row.get("activity").asText());
    }

    @Test
    void historyRejectsABadFilter() {
        assertEquals(1, app.run("history", "--course", "EECS3311", "--since", "tomorrow"));
        assertTrue(app.output.contains("YYYY-MM-DD"), app.output);

        assertEquals(1, app.run("history", "--course", "EECS3311", "--type", "sleep"));
        assertTrue(app.output.contains("Unknown activity type"), app.output);

        assertEquals(1, app.run("history", "--course", "EECS3311", "--since", "2026-11-05", "--until", "2026-11-01"));
        assertTrue(app.output.contains("start date"), app.output);
    }

    @Test
    void aCoachConversationAppearsOnceInTheHistory() {
        app.llm.enqueueText("Hello!");

        assertEquals(0, app.run("coach", "--course", "EECS3311", "--once", "Help me study"), app.output);
        assertEquals(0, app.run("history", "--course", "EECS3311", "--type", "coach"), app.output);

        assertTrue(app.output.contains("Coach conversation"), app.output);
        assertTrue(app.output.contains("Help me study"), app.output);
    }

    @Test
    void exportWritesTheFile() throws IOException {
        takeAQuizAnsweringBothWrongOrRight();
        Path csv = dir.resolve("progress.csv");
        Path markdown = dir.resolve("progress.md");

        assertEquals(0, app.run("progress", "export", "--course", "EECS3311", "--format", "csv", "--out", csv.toString()),
                app.output);
        assertTrue(app.output.contains("Report saved"), app.output);
        assertTrue(Files.readString(csv).contains("Quiz taken"));

        assertEquals(0, app.run("progress", "export", "--course", "EECS3311", "--format", "markdown", "--out",
                markdown.toString(), "--json"), app.output);
        assertTrue(mapper.readTree(app.output).get("file").asText().endsWith("progress.md"));
        assertTrue(Files.readString(markdown).startsWith("# Progress report: EECS3311"));
    }

    @Test
    void exportReportsABadFormatOrLocation() {
        assertEquals(1, app.run("progress", "export", "--course", "EECS3311", "--format", "pdf", "--out",
                dir.resolve("x.pdf").toString()));
        assertTrue(app.output.contains("Unknown report format"), app.output);

        assertEquals(1, app.run("progress", "export", "--course", "EECS3311", "--format", "csv", "--out",
                dir.resolve("missing").resolve("x.csv").toString()));
        assertTrue(app.output.contains("Choose another location"), app.output);
    }
}
