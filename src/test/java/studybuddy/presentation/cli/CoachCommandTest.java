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
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import studybuddy.ai.LLMException;
import studybuddy.ai.LLMResponse;
import studybuddy.ai.ToolCall;
import studybuddy.application.StudyAgentFacade;

class CoachCommandTest {

    private static final String ONE_CARD = "{\"cards\": [{\"front\": \"Front\", \"back\": \"Back\", \"excerpt\": 1, "
            + "\"page\": 1, \"concept\": null}]}";

    @TempDir
    Path dir;

    private CliTestApp app;
    private final ObjectMapper mapper = new ObjectMapper();
    private long courseId;

    @BeforeEach
    void setUp() throws IOException {
        app = new CliTestApp(dir.resolve("coach.db"));
        StudyAgentFacade facade = app.facade();
        courseId = facade.createCourse("EECS3311", "").getId();
        facade.uploadMaterial(courseId, Files.writeString(dir.resolve("patterns.txt"),
                "The Facade pattern gives one simple interface to a whole subsystem. ".repeat(20)));
    }

    @AfterEach
    void tearDown() {
        app.close();
    }

    private void scriptDeck() {
        app.llm.enqueue(LLMResponse.toolCalls(new ToolCall("1", "generate_flashcards",
                Map.of("courseId", courseId, "count", 5))));
        app.llm.enqueueText(ONE_CARD);
        app.llm.enqueueText("Your deck is ready.");
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
    void onceShowsStepsAnswerAndCreatedItems() {
        scriptDeck();

        assertEquals(0, app.run("coach", "--once", "Make cards", "--course", "EECS3311"), app.output);

        assertTrue(app.output.contains("... Generating 5-card flashcard deck"), app.output);
        assertTrue(app.output.contains("Coach: Your deck is ready."), app.output);
        assertTrue(app.output.contains("Created: Flashcard deck #1 (1 cards)"), app.output);
    }

    @Test
    void onceWithJsonPrintsTheAnswerAndFullTrace() throws IOException {
        scriptDeck();

        assertEquals(0, app.run("coach", "--once", "Make cards", "--json"), app.output);

        JsonNode json = mapper.readTree(app.output);
        assertEquals("Your deck is ready.", json.get("response").asText());
        assertEquals("COMPLETED", json.get("status").asText());
        JsonNode step = json.get("steps").get(0);
        assertEquals("generate_flashcards", step.get("tool").asText());
        assertEquals(5, step.get("arguments").get("count").asInt());
        assertEquals("SUCCEEDED", step.get("status").asText());
        assertTrue(step.get("result").asText().contains("Created Flashcard deck"));
        assertTrue(step.has("millis"));
        assertEquals("DECK", json.get("created").get(0).get("type").asText());
        assertEquals(1, json.get("created").get(0).get("id").asInt());
    }

    @Test
    void aFailedRunExitsWithAnError() throws IOException {
        app.llm.enqueueFailure(new LLMException("rate limited", true));

        assertEquals(1, app.run("coach", "--once", "Help", "--json"));

        assertEquals("FAILED", mapper.readTree(app.output).get("status").asText());
    }

    @Test
    void aFailingStepIsShown() {
        app.llm.enqueue(LLMResponse.toolCalls(new ToolCall("1", "generate_flashcards",
                Map.of("courseId", courseId, "count", 99))));
        app.llm.enqueueText("That is too many.");

        assertEquals(0, app.run("coach", "--once", "Make 99 cards"));

        assertTrue(app.output.contains("... failed: Invalid arguments"), app.output);
    }

    @Test
    void conversationRemembersEarlierMessagesUntilExit() {
        app.llm.enqueueText("Hello!");
        app.llm.enqueueText("You said hello.");

        assertEquals(0, runWithInput("hello\n\nwhat did I say?\nexit\nignored\n", "coach", "--course", "EECS3311"), app.output);

        assertTrue(app.output.contains("Coach: Hello!"), app.output);
        assertTrue(app.output.contains("Coach: You said hello."), app.output);
        assertEquals("hello", app.llm.lastRequest().messages().get(0).text());
        assertEquals(0, app.llm.remaining());
    }

    @Test
    void conversationEndsAtTheEndOfInput() {
        assertEquals(0, runWithInput("", "coach"));

        assertEquals(0, app.llm.getRequests().size());
    }

    @Test
    void jsonModeHasNoPrompts() throws IOException {
        app.llm.enqueueText("Hello!");

        assertEquals(0, runWithInput("hi\n", "coach", "--json"), app.output);

        assertEquals("Hello!", mapper.readTree(app.output).get("response").asText());
        assertFalse(app.output.contains("You:"));
    }

    @Test
    void unknownCourseIsAnError() {
        assertEquals(1, app.run("coach", "--once", "Hi", "--course", "Nope"));
    }
}
