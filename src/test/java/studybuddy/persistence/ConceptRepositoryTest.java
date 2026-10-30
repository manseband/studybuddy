package studybuddy.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.LocalDateTime;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import studybuddy.domain.Chunk;
import studybuddy.domain.Concept;
import studybuddy.domain.Course;
import studybuddy.domain.Material;
import studybuddy.domain.MaterialStatus;

class ConceptRepositoryTest {

    private Database db;
    private ConceptRepository repo;
    private long courseId;
    private long materialId;

    @BeforeEach
    void setUp() {
        db = Database.inMemory();
        repo = new ConceptRepository(db);
        courseId = new CourseRepository(db).save(Course.create("EECS3311", "", null)).getId();
        Material material = new Material(0, courseId, "notes.pdf", "pdf", MaterialStatus.INDEXED, "h", 3, 30,
                LocalDateTime.of(2026, 10, 30, 9, 0));
        material.setChunks(List.of(new Chunk(0, 0, courseId, 0, "text", 1)));
        materialId = new MaterialRepository(db).save(material).getId();
    }

    @AfterEach
    void tearDown() {
        db.close();
    }

    private Concept concept(String name, int importance, Integer... pages) {
        Concept c = new Concept(0, courseId, name, name + " definition", importance);
        c.addSourcePages(List.of(pages));
        return repo.save(c);
    }

    /** concept_id of a flashcard row, read directly from the table. */
    private Long flashcardConcept(long cardId) {
        return db.withConnection(c -> {
            try (PreparedStatement ps = c.prepareStatement("SELECT concept_id FROM flashcards WHERE id = ?")) {
                ps.setLong(1, cardId);
                try (ResultSet rs = ps.executeQuery()) {
                    rs.next();
                    long value = rs.getLong(1);
                    return rs.wasNull() ? null : value;
                }
            }
        });
    }

    private long insertFlashcard(long conceptId) {
        return db.withConnection(c -> {
            try (PreparedStatement ps = c.prepareStatement("INSERT INTO flashcards(course_id, deck_id, concept_id, "
                    + "front, back, next_due) VALUES (?, 1, ?, 'f', 'b', '2026-10-30')",
                    java.sql.Statement.RETURN_GENERATED_KEYS)) {
                ps.setLong(1, courseId);
                ps.setLong(2, conceptId);
                ps.executeUpdate();
                return SqlUtil.generatedId(ps);
            }
        });
    }

    @Test
    void savesConceptsWithPagesAndPrerequisites() {
        Concept iface = concept("Interface", 3, 1);
        Concept observer = concept("Observer", 5, 2, 3);
        observer.addPrerequisite(iface);
        repo.save(observer);

        List<Concept> all = repo.findByCourse(courseId);
        assertEquals(List.of("Interface", "Observer"), all.stream().map(Concept::getName).toList());
        Concept loaded = all.get(1);
        assertEquals(List.of(2, 3), loaded.getSourcePages());
        assertEquals("Interface", loaded.getPrerequisites().get(0).getName());
        assertEquals("Interface", repo.findById(observer.getId()).orElseThrow().getPrerequisites().get(0).getName());

        observer.setDefinition("Notifies observers");
        observer.removePrerequisite(iface);
        repo.save(observer);
        Concept updated = repo.findById(observer.getId()).orElseThrow();
        assertEquals("Notifies observers", updated.getDefinition());
        assertTrue(updated.getPrerequisites().isEmpty());
    }

    @Test
    void linksMaterials() {
        Concept facade = concept("Facade", 4, 1);
        repo.linkMaterial(facade.getId(), materialId);
        repo.linkMaterial(facade.getId(), materialId);

        assertEquals(List.of(materialId), repo.findMaterialIds(facade.getId()));
    }

    @Test
    void deletingAConceptUntagsItsFlashcards() {
        Concept facade = concept("Facade", 4, 1);
        long card = insertFlashcard(facade.getId());

        repo.delete(facade.getId());

        assertNull(flashcardConcept(card));
        assertFalse(repo.findById(facade.getId()).isPresent());
    }

    @Test
    void mergeMovesTagsLinksAndPrerequisites() {
        Concept iface = concept("Interface", 3, 1);
        Concept observers = concept("Observers", 4, 2);
        Concept observer = concept("Observer", 5, 3);
        Concept mvc = concept("MVC", 4, 3);
        observers.addPrerequisite(iface);
        repo.save(observers);
        mvc.addPrerequisite(observers);
        repo.save(mvc);
        repo.linkMaterial(observers.getId(), materialId);
        long card = insertFlashcard(observers.getId());

        repo.merge(observers.getId(), observer.getId());

        assertFalse(repo.findById(observers.getId()).isPresent());
        assertEquals(observer.getId(), flashcardConcept(card));
        assertEquals(List.of(materialId), repo.findMaterialIds(observer.getId()));
        assertEquals("Interface", repo.findById(observer.getId()).orElseThrow().getPrerequisites().get(0).getName());
        assertEquals("Observer", repo.findById(mvc.getId()).orElseThrow().getPrerequisites().get(0).getName());
        assertThrows(IllegalArgumentException.class, () -> repo.merge(observer.getId(), observer.getId()));
    }
}
