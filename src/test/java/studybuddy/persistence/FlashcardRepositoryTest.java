package studybuddy.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import studybuddy.domain.Concept;
import studybuddy.domain.Course;
import studybuddy.domain.Flashcard;
import studybuddy.domain.FlashcardReview;
import studybuddy.domain.Rating;

class FlashcardRepositoryTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 11, 6);

    private Database db;
    private FlashcardRepository cards;
    private ConceptRepository concepts;
    private long courseId;

    @BeforeEach
    void setUp() {
        db = Database.inMemory();
        cards = new FlashcardRepository(db);
        concepts = new ConceptRepository(db);
        courseId = new CourseRepository(db).save(Course.create("EECS3311", "")).getId();
    }

    @AfterEach
    void tearDown() {
        db.close();
    }

    private Flashcard newCard(Long conceptId, String front) {
        return Flashcard.create(courseId, 0, conceptId, front, "Back", 4, TODAY);
    }

    @Test
    void savesAndLoadsEveryField() {
        long conceptId = concepts.save(new Concept(0, courseId, "Facade", "", 3)).getId();
        Flashcard saved = cards.saveDeck(List.of(newCard(conceptId, "Front"))).get(0);

        Flashcard loaded = cards.findById(saved.getId()).orElseThrow();

        assertEquals(courseId, loaded.getCourseId());
        assertEquals(saved.getDeckId(), loaded.getDeckId());
        assertEquals(conceptId, loaded.getConceptId());
        assertEquals("Front", loaded.getFront());
        assertEquals("Back", loaded.getBack());
        assertEquals(4, loaded.getSourcePage());
        assertEquals(1, loaded.getBox());
        assertEquals(TODAY, loaded.getNextDue());
        assertEquals(0, loaded.getReviewCount());
    }

    @Test
    void untaggedCardKeepsItsNullConcept() {
        Flashcard saved = cards.saveDeck(List.of(newCard(null, "Front"))).get(0);

        assertNull(cards.findById(saved.getId()).orElseThrow().getConceptId());
    }

    @Test
    void deckIdsAreNewEachTimeAndListedNewestFirst() {
        long first = cards.saveDeck(List.of(newCard(null, "A"), newCard(null, "B"))).get(0).getDeckId();
        long second = cards.saveDeck(List.of(newCard(null, "C"))).get(0).getDeckId();

        assertEquals(first + 1, second);
        assertEquals(List.of("C", "A", "B"), cards.findByCourse(courseId).stream().map(Flashcard::getFront).toList());
        assertEquals(List.of("A", "B"), cards.findByDeck(first).stream().map(Flashcard::getFront).toList());
    }

    @Test
    void reviewSavesTheScheduleAndTheRatingTogether() {
        Flashcard card = cards.saveDeck(List.of(newCard(null, "Front"))).get(0);
        card.review(Rating.EASY, TODAY);
        LocalDateTime at = LocalDateTime.of(2026, 11, 6, 9, 30);

        cards.recordReview(card, Rating.EASY, at);

        Flashcard loaded = cards.findById(card.getId()).orElseThrow();
        assertEquals(3, loaded.getBox());
        assertEquals(TODAY.plusDays(4), loaded.getNextDue());
        assertEquals(1, loaded.getReviewCount());
        assertEquals(List.of(new FlashcardReview(card.getId(), courseId, null, Rating.EASY, at)),
                cards.findReviews(courseId));
    }

    @Test
    void reviewsAreListedOldestFirst() {
        Flashcard card = cards.saveDeck(List.of(newCard(null, "Front"))).get(0);
        cards.recordReview(card, Rating.GOOD, LocalDateTime.of(2026, 11, 6, 10, 0));
        cards.recordReview(card, Rating.AGAIN, LocalDateTime.of(2026, 11, 5, 10, 0));

        assertEquals(List.of(Rating.AGAIN, Rating.GOOD),
                cards.findReviews(courseId).stream().map(FlashcardReview::rating).toList());
    }

    @Test
    void deletingAConceptUntagsCardsAndReviews() {
        long conceptId = concepts.save(new Concept(0, courseId, "Facade", "", 3)).getId();
        Flashcard card = cards.saveDeck(List.of(newCard(conceptId, "Front"))).get(0);
        cards.recordReview(card, Rating.GOOD, LocalDateTime.of(2026, 11, 6, 10, 0));

        concepts.delete(conceptId);

        assertNull(cards.findById(card.getId()).orElseThrow().getConceptId());
        assertNull(cards.findReviews(courseId).get(0).conceptId());
    }

    @Test
    void deleteRemovesTheCardAndItsReviews() {
        Flashcard card = cards.saveDeck(List.of(newCard(null, "Front"))).get(0);
        cards.recordReview(card, Rating.GOOD, LocalDateTime.of(2026, 11, 6, 10, 0));

        cards.delete(card.getId());

        assertTrue(cards.findById(card.getId()).isEmpty());
        assertTrue(cards.findReviews(courseId).isEmpty());
    }
}
