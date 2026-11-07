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
import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import studybuddy.application.StudyAgentFacade;
import studybuddy.domain.Concept;
import studybuddy.domain.Material;

class FlashcardsCommandTest {

    private static final String TWO_CARDS = """
            {"cards": [
              {"front": "What is a Facade?", "back": "One simple interface.", "excerpt": 1, "concept": "Facade"},
              {"front": "What does it hide?", "back": "A subsystem.", "excerpt": 1, "concept": null}]}""";

    @TempDir
    Path dir;

    private CliTestApp app;
    private final ObjectMapper mapper = new ObjectMapper();
    private long courseId;
    private Material material;

    @BeforeEach
    void setUp() throws IOException {
        app = new CliTestApp(dir.resolve("cards.db"));
        StudyAgentFacade facade = app.facade();
        courseId = facade.createCourse("EECS3311", "").getId();
        material = facade.uploadMaterial(courseId, Files.writeString(dir.resolve("patterns.txt"),
                "The Facade pattern gives one simple interface to a whole subsystem. ".repeat(20)));
    }

    @AfterEach
    void tearDown() {
        app.close();
    }

    private void generateDeck() {
        app.llm.enqueueText(TWO_CARDS);
        assertEquals(0, app.run("flashcards", "generate", "--course", "EECS3311", "--count", "5"), app.output);
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
    void generateSavesADeckAndNoticesFewerCards() {
        generateDeck();

        assertTrue(app.output.contains("Created Deck #1 with 2 card(s)"), app.output);
        assertTrue(app.output.contains("A: A subsystem. (concept: None, due"), app.output);
        assertTrue(app.output.contains("Q: What is a Facade?"), app.output);
        assertTrue(app.output.contains("only 2 of the 5"), app.output);
        assertEquals(2, app.facade().listFlashcards(courseId).size());
    }

    @Test
    void previewDoesNotSave() throws IOException {
        app.llm.enqueueText(TWO_CARDS);

        assertEquals(0, app.run("flashcards", "generate", "--course", "EECS3311", "--count", "5", "--preview", "--json"));

        JsonNode json = mapper.readTree(app.output);
        assertFalse(json.get("saved").asBoolean());
        assertEquals(2, json.get("cards").size());
        assertTrue(app.facade().listFlashcards(courseId).isEmpty());
    }

    @Test
    void conceptsCanBeChosenByNameOrId() {
        Concept concept = new Concept(0, courseId, "Facade", "A simple interface", 3);
        app.facade().saveConcepts(material.getId(), List.of(concept));
        app.llm.enqueueText(TWO_CARDS);
        assertEquals(0, app.run("flashcards", "generate", "--course", "EECS3311", "--concepts", "facade"), app.output);

        app.llm.enqueueText(TWO_CARDS.replace("What", "Which"));
        assertEquals(0, app.run("flashcards", "generate", "--course", "EECS3311", "--concepts",
                String.valueOf(concept.getId())), app.output);

        assertEquals(concept.getId(), app.facade().listFlashcards(courseId).get(0).getConceptId());
    }

    @Test
    void unknownConceptAndBadCountAreErrors() {
        assertEquals(1, app.run("flashcards", "generate", "--course", "EECS3311", "--concepts", "Quantum"));
        assertTrue(app.output.contains("no concept \"Quantum\""), app.output);

        assertEquals(1, app.run("flashcards", "generate", "--course", "EECS3311", "--count", "99"));
        assertTrue(app.output.contains("between 5 and 20"), app.output);
    }

    @Test
    void listAndShowDescribeDecks() {
        assertEquals(0, app.run("flashcards", "list", "--course", "EECS3311"));
        assertTrue(app.output.contains("No flashcards yet"), app.output);

        generateDeck();

        assertEquals(0, app.run("flashcards", "list", "--course", "EECS3311"));
        assertTrue(app.output.contains("Deck #1"), app.output);
        assertTrue(app.output.contains("2 card(s) due today"), app.output);

        assertEquals(0, app.run("flashcards", "show", "1"));
        assertTrue(app.output.contains("A: A subsystem."), app.output);

        assertEquals(1, app.run("flashcards", "show", "99"));
    }

    @Test
    void deleteRemovesTheDeckAndKeepsMastery() throws IOException {
        Concept concept = new Concept(0, courseId, "Facade", "", 3);
        app.facade().saveConcepts(material.getId(), List.of(concept));
        generateDeck();
        runWithInput("\na\nq\n", "flashcards", "review", "--deck", "1");

        assertEquals(0, app.run("flashcards", "delete", "1"), app.output);
        assertTrue(app.output.contains("Deleted Deck #1"), app.output);
        assertTrue(app.facade().listFlashcards(courseId).isEmpty());

        assertEquals(0, app.run("weak-concepts", "--course", "EECS3311", "--json"));
        assertEquals(1, mapper.readTree(app.output).get("concepts").get(0).get("evidenceCount").asInt());

        assertEquals(1, app.run("flashcards", "delete", "1"));
    }

    @Test
    void reviewShowsCardsAndUpdatesTheSchedule() {
        generateDeck();

        assertEquals(0, runWithInput("\nnonsense\ng\n\na\n", "flashcards", "review", "--deck", "1"), app.output);

        assertTrue(app.output.contains("Card 1 of 2"), app.output);
        assertTrue(app.output.contains("A: One simple interface."), app.output);
        assertTrue(app.output.contains("Reviewed 2 card(s) {good=1, again=1}"), app.output);
        assertTrue(app.output.contains("next card is due on " + LocalDate.now().plusDays(1)), app.output);
        assertEquals(2, app.facade().listFlashcards(courseId).get(0).getBox());
        assertTrue(app.facade().getDueCards(courseId).isEmpty());
    }

    @Test
    void reviewCanStopEarlyAndKeepsTheRest() {
        generateDeck();

        assertEquals(0, runWithInput("\ne\nq\n", "flashcards", "review", "--course", "EECS3311"), app.output);

        assertTrue(app.output.contains("Reviewed 1 card(s)"), app.output);
        assertEquals(1, app.facade().getDueCards(courseId).size());
    }

    @Test
    void reviewWithJsonPrintsOnlyJson() throws IOException {
        generateDeck();

        assertEquals(0, runWithInput("\neasy\n", "flashcards", "review", "--deck", "1", "--json"), app.output);

        JsonNode json = mapper.readTree(app.output);
        assertEquals(1, json.get("reviewed").asInt());
        assertEquals(1, json.get("ratings").get("easy").asInt());
        assertEquals(3, json.get("cards").get(0).get("box").asInt());
    }

    @Test
    void nothingDueSaysWhenTheNextCardIs() {
        generateDeck();
        runWithInput("\ng\n\ng\n", "flashcards", "review", "--deck", "1");

        assertEquals(0, runWithInput("", "flashcards", "review", "--deck", "1"));

        assertTrue(app.output.contains("No cards reviewed. The next card is due on"), app.output);
    }

    @Test
    void reviewNeedsExactlyOneScope() {
        assertEquals(1, app.run("flashcards", "review"));
        assertEquals(1, app.run("flashcards", "review", "--deck", "1", "--course", "EECS3311"));
        assertTrue(app.output.contains("either --deck or --course"), app.output);
    }

    @Test
    void reviewedCardsCountTowardsMastery() throws IOException {
        Concept concept = new Concept(0, courseId, "Facade", "", 3);
        app.facade().saveConcepts(material.getId(), List.of(concept));
        generateDeck();
        runWithInput("\na\nq\n", "flashcards", "review", "--deck", "1");

        assertEquals(0, app.run("weak-concepts", "--course", "EECS3311", "--json"));

        assertEquals(1, mapper.readTree(app.output).get("concepts").get(0).get("evidenceCount").asInt());
    }
}
