package studybuddy.presentation.gui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

import java.awt.GraphicsEnvironment;
import java.lang.reflect.InvocationTargetException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.function.BooleanSupplier;

import javax.swing.SwingUtilities;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import studybuddy.ai.FakeLLMClient;
import studybuddy.application.StudyAgentFacade;
import studybuddy.domain.Concept;
import studybuddy.domain.Course;
import studybuddy.domain.Flashcard;
import studybuddy.domain.Material;
import studybuddy.domain.Rating;
import studybuddy.persistence.Database;

/** Drives the Flashcards tab without showing a window; the panel must not open a dialog here. */
class FlashcardPanelTest {

    @TempDir
    Path dir;

    private Database db;
    private FakeLLMClient llm;
    private StudyAgentFacade facade;
    private Course course;
    private FlashcardPanel panel;

    @BeforeEach
    void setUp() throws Exception {
        assumeFalse(GraphicsEnvironment.isHeadless());
        db = Database.inMemory();
        llm = new FakeLLMClient();
        facade = GuiTestFacade.create(db, llm);
        course = facade.createCourse("EECS3311", "");
        facade.uploadMaterial(course.getId(), Files.writeString(dir.resolve("notes.txt"),
                "The Facade pattern gives one simple interface to a whole subsystem. ".repeat(20)));
        SwingUtilities.invokeAndWait(() -> {
            panel = new FlashcardPanel(facade);
            panel.setCourse(course);
        });
    }

    @AfterEach
    void tearDown() {
        if (db != null) {
            db.close();
        }
    }

    private boolean onEdt(BooleanSupplier check) throws InterruptedException, InvocationTargetException {
        boolean[] result = new boolean[1];
        SwingUtilities.invokeAndWait(() -> result[0] = check.getAsBoolean());
        return result[0];
    }

    private void waitFor(BooleanSupplier check) throws Exception {
        for (int i = 0; i < 100 && !onEdt(check); i++) {
            Thread.sleep(50);
        }
        assertTrue(onEdt(check), "condition not reached");
    }

    private List<Flashcard> generated() {
        llm.enqueueText("""
                {"cards": [
                  {"front": "What is a Facade?", "back": "One simple interface.", "excerpt": 1, "concept": "Facade"},
                  {"front": "What does it hide?", "back": "A subsystem.", "excerpt": 1, "concept": null}]}""");
        return facade.generateFlashcards(course.getId(), List.of(), List.of(), 5);
    }

    private void saveConceptAndDeck() throws Exception {
        Material material = facade.listMaterials(course.getId()).get(0);
        facade.saveConcepts(material.getId(), List.of(new Concept(0, course.getId(), "Facade", "Hides things.", 3)));
        facade.saveFlashcards(generated());
        SwingUtilities.invokeAndWait(panel::refresh);
        waitFor(() -> panel.deckCount() == 1);
    }

    @Test
    void theCardTableShowsEachConceptAndNotesTheUntaggedCards() throws Exception {
        saveConceptAndDeck();

        SwingUtilities.invokeAndWait(() -> panel.selectDeck(0));

        assertEquals("Facade", panel.cardCell(0, 2));
        assertEquals("None", panel.cardCell(1, 2));
        assertEquals("1 card has no concept, so reviewing it doesn't affect mastery.", panel.untaggedNoteText());

        SwingUtilities.invokeAndWait(panel::clearDeckSelection);
        assertNull(panel.untaggedNoteText());
    }

    @Test
    void deletingADeckEmptiesTheListAndClearsTheSelection() throws Exception {
        saveConceptAndDeck();
        long deckId = facade.listFlashcards(course.getId()).get(0).getDeckId();
        SwingUtilities.invokeAndWait(() -> panel.selectDeck(0));

        SwingUtilities.invokeAndWait(() -> panel.deleteDeck(deckId));
        waitFor(() -> facade.listFlashcards(course.getId()).isEmpty());
        SwingUtilities.invokeAndWait(panel::refresh);

        waitFor(() -> panel.deckCount() == 0);
        assertNull(panel.untaggedNoteText());
    }

    @Test
    void savesEditedCardsFromThePreview() throws Exception {
        List<Flashcard> cards = generated();

        SwingUtilities.invokeAndWait(() -> {
            panel.showPreview(cards, List.of(), 5);
            panel.editPreview(0, "Edited front", "Edited back");
            panel.savePreview();
        });
        waitFor(() -> FlashcardPanel.DECKS_VIEW.equals(panel.currentView()) && panel.deckCount() == 1);

        List<Flashcard> saved = facade.listFlashcards(course.getId());
        assertEquals("Edited front", saved.get(0).getFront());
        assertEquals("Edited back", saved.get(0).getBack());
        assertEquals("What does it hide?", saved.get(1).getFront());
        assertEquals("2 card(s) due today", panel.dueText());
    }

    @Test
    void reviewsEachDueCardThenSaysWhenTheNextIsDue() throws Exception {
        facade.saveFlashcards(generated());
        SwingUtilities.invokeAndWait(panel::refresh);
        waitFor(() -> panel.deckCount() == 1);

        SwingUtilities.invokeAndWait(panel::reviewDueCards);
        waitFor(() -> FlashcardPanel.REVIEW_VIEW.equals(panel.currentView())
                && "What is a Facade?".equals(panel.reviewFrontText()));

        SwingUtilities.invokeAndWait(panel::revealAnswer);
        assertEquals("One simple interface.", panel.reviewBackText());

        SwingUtilities.invokeAndWait(() -> panel.rate(Rating.GOOD));
        waitFor(() -> "What does it hide?".equals(panel.reviewFrontText()));
        SwingUtilities.invokeAndWait(panel::revealAnswer);
        SwingUtilities.invokeAndWait(() -> panel.rate(Rating.AGAIN));

        waitFor(() -> panel.reviewFrontText().contains("You reviewed 2 card(s)")
                && panel.reviewBackText().contains("next card is due"));
        assertTrue(facade.getDueCards(course.getId()).isEmpty());
        assertEquals(2, facade.listFlashcards(course.getId()).get(0).getBox());
    }

    @Test
    void nothingDueShowsAMessageInsteadOfACard() throws Exception {
        List<Flashcard> saved = facade.saveFlashcards(generated());
        saved.forEach(card -> facade.reviewFlashcard(card.getId(), Rating.EASY));
        SwingUtilities.invokeAndWait(panel::refresh);
        waitFor(() -> panel.deckCount() == 1);

        SwingUtilities.invokeAndWait(panel::reviewDueCards);

        waitFor(() -> panel.reviewFrontText().contains("nothing to review")
                && panel.reviewBackText().contains("next card is due"));
    }
}
