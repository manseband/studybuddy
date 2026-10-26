package studybuddy.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import org.junit.jupiter.api.Test;

class CourseEntitiesTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 10, 26);

    @Test
    void courseRequiresAName() {
        assertThrows(IllegalArgumentException.class, () -> Course.create("  ", "EECS3311", null));
        assertEquals("Software Design (EECS3311)", Course.create(" Software Design ", "EECS3311", null).toString());
    }

    @Test
    void leitnerReviewMovesCardsBetweenBoxes() {
        Flashcard card = Flashcard.create(1, 1, null, "What is a Facade?", "A simple interface to a subsystem",
                3, TODAY);
        assertTrue(card.isDue(TODAY));

        card.review(Rating.GOOD, TODAY);
        assertEquals(2, card.getBox());
        assertEquals(TODAY.plusDays(2), card.getNextDue());
        assertFalse(card.isDue(TODAY.plusDays(1)));

        card.review(Rating.EASY, TODAY);
        assertEquals(4, card.getBox());
        assertEquals(TODAY.plusDays(8), card.getNextDue());

        card.review(Rating.HARD, TODAY);
        assertEquals(4, card.getBox());

        card.review(Rating.AGAIN, TODAY);
        assertEquals(1, card.getBox());
        assertEquals(TODAY.plusDays(1), card.getNextDue());
        assertEquals(4, card.getReviewCount());
    }

    @Test
    void leitnerBoxNeverExceedsFive() {
        Flashcard card = Flashcard.create(1, 1, null, "front", "back", 1, TODAY);
        for (int i = 0; i < 5; i++) {
            card.review(Rating.EASY, TODAY);
        }
        assertEquals(Flashcard.MAX_BOX, card.getBox());
        assertEquals(TODAY.plusDays(16), card.getNextDue());
    }

    @Test
    void flashcardRejectsEmptySides() {
        assertThrows(IllegalArgumentException.class, () -> Flashcard.create(1, 1, null, "front", " ", 1, TODAY));
    }

    @Test
    void conceptNamesAreNormalizedForDeduplication() {
        assertEquals("design pattern", Concept.normalize("Design Patterns!"));
        assertEquals("strategy", Concept.normalize("strategies"));
        assertEquals("class", Concept.normalize("Class"));
        assertTrue(new Concept(0, 1, "Observer Pattern", "", 3).matches("observer patterns"));
    }

    @Test
    void conceptPrerequisitesIgnoreSelfAndDuplicates() {
        Concept observer = new Concept(1, 1, "Observer", "", 4);
        Concept iface = new Concept(2, 1, "Interface", "", 3);
        observer.addPrerequisite(iface);
        observer.addPrerequisite(new Concept(3, 1, "Interfaces", "", 3));
        observer.addPrerequisite(observer);

        assertEquals(List.of(iface), observer.getPrerequisites());
        assertEquals(5, new Concept(0, 1, "x", "", 9).getImportance());
    }

    @Test
    void materialFiltersChunksByPageRange() {
        Material material = new Material(0, 1, "notes.pdf", "PDF", MaterialStatus.INDEXED, "abc", 5, 900,
                LocalDateTime.of(2026, 10, 26, 9, 0));
        material.setChunks(List.of(new Chunk(0, 0, 1, 0, "first chunk", 1),
                new Chunk(0, 0, 1, 1, "second chunk", 3),
                new Chunk(0, 0, 1, 2, "third chunk", 5)));
        material.setId(42);

        assertEquals("pdf", material.getFileType());
        assertEquals(2, material.chunksInPages(2, 5).size());
        assertEquals(42, material.getChunks().get(0).getMaterialId());
        assertTrue(material.hasText());
    }

    @Test
    void chunkEmbeddingIsTiedToItsModel() {
        Chunk chunk = new Chunk(0, 1, 1, 0, "some words here", 1);
        chunk.setEmbedding(new float[] {0.1f, 0.2f}, "gemini-embedding-001");

        assertTrue(chunk.hasEmbeddingFrom("gemini-embedding-001"));
        assertFalse(chunk.hasEmbeddingFrom("other-model"));
        assertEquals(3, chunk.wordCount());
    }

    @Test
    void planCompletionIsWeightedByMinutes() {
        StudyPlan plan = new StudyPlan(0, 1, TODAY.plusDays(10), LocalDateTime.of(2026, 10, 26, 9, 0));
        StudySession first = new StudySession(1, TODAY.plusDays(1), 30, List.of("Observer"), "", false);
        StudySession second = new StudySession(2, TODAY, 90, List.of("Strategy"), "", false);
        plan.addSession(first);
        plan.addSession(second);

        assertEquals(0.0, plan.getCompletion());
        second.markDone();
        assertEquals(0.75, plan.getCompletion());
        assertEquals(TODAY, plan.getSessions().get(0).getDate());
        assertEquals(List.of(first), plan.remainingFrom(TODAY));
    }
}
