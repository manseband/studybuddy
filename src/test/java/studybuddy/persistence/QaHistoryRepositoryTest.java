package studybuddy.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDateTime;
import java.util.List;

import org.junit.jupiter.api.Test;

import studybuddy.domain.Answer;
import studybuddy.domain.Citation;
import studybuddy.domain.Course;
import studybuddy.domain.QaEntry;

class QaHistoryRepositoryTest {

    @Test
    void storesAnswersWithCitationsNewestFirst() {
        try (Database db = Database.inMemory()) {
            long courseId = new CourseRepository(db).save(Course.create("EECS3311", "", null)).getId();
            QaHistoryRepository repo = new QaHistoryRepository(db);
            Answer answer = new Answer("Facade simplifies [1].",
                    List.of(new Citation(1, 10, 2, "notes.pdf", 3, "Facade text")), true, false, false);

            QaEntry first = repo.save(new QaEntry(0, courseId, "What is Facade?", answer,
                    LocalDateTime.of(2026, 10, 30, 9, 0)));
            repo.save(new QaEntry(0, courseId, "What is gravity?", Answer.generalKnowledge("A force."),
                    LocalDateTime.of(2026, 10, 30, 10, 0)));

            List<QaEntry> history = repo.findByCourse(courseId);
            assertEquals(2, history.size());
            assertEquals("What is gravity?", history.get(0).question());
            assertTrue(history.get(0).answer().generalKnowledge());

            QaEntry loaded = repo.findById(first.id()).orElseThrow();
            assertEquals(answer, loaded.answer());
            assertThrows(IllegalArgumentException.class, () -> repo.save(loaded));
        }
    }
}
