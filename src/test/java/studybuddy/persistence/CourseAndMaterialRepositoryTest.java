package studybuddy.persistence;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import studybuddy.domain.Chunk;
import studybuddy.domain.Course;
import studybuddy.domain.Material;
import studybuddy.domain.MaterialStatus;

class CourseAndMaterialRepositoryTest {

    private Database db;
    private CourseRepository courses;
    private MaterialRepository materials;

    @BeforeEach
    void setUp() {
        db = Database.inMemory();
        courses = new CourseRepository(db);
        materials = new MaterialRepository(db);
    }

    @AfterEach
    void tearDown() {
        db.close();
    }

    private Material newMaterial(long courseId, String hash, Chunk... chunks) {
        Material m = new Material(0, courseId, "notes.md", "md", MaterialStatus.INDEXED, hash, 2, 40,
                LocalDateTime.of(2026, 10, 26, 10, 0));
        m.setChunks(List.of(chunks));
        return m;
    }

    @Test
    void savesUpdatesAndFindsCourses() {
        Course course = courses.save(Course.create("Software Design", "EECS3311", LocalDate.of(2026, 12, 10)));
        assertNotEquals(0, course.getId());

        course.setName("Software Design F26");
        courses.save(course);

        Course loaded = courses.findById(course.getId()).orElseThrow();
        assertEquals("Software Design F26", loaded.getName());
        assertEquals(LocalDate.of(2026, 12, 10), loaded.getExamDate());
        assertTrue(courses.findByName("software design f26").isPresent());
        assertEquals(1, courses.findAll().size());
        assertThrows(PersistenceException.class, () -> courses.save(Course.create("SOFTWARE DESIGN F26", "", null)));
    }

    @Test
    void savesMaterialWithChunksAndEmbeddings() {
        long courseId = courses.save(Course.create("EECS3311", "", null)).getId();
        Chunk first = new Chunk(0, 0, 0, 0, "Facade gives a simple interface", 1);
        first.setEmbedding(new float[] {0.25f, -1.5f, 3f}, "gemini-embedding-001");
        Chunk second = new Chunk(0, 0, 0, 1, "Observer notifies listeners", 2);

        Material saved = materials.save(newMaterial(courseId, "hash-1", first, second));

        Material loaded = materials.findById(saved.getId()).orElseThrow();
        assertEquals(2, loaded.getChunkCount());
        assertEquals(courseId, loaded.getChunks().get(1).getCourseId());
        assertArrayEquals(new float[] {0.25f, -1.5f, 3f}, loaded.getChunks().get(0).getEmbedding());
        assertNull(loaded.getChunks().get(1).getEmbedding());
        assertTrue(materials.findByHash(courseId, "hash-1").isPresent());
        assertFalse(materials.findByHash(courseId, "other").isPresent());
    }

    @Test
    void updatesEmbeddingsLater() {
        long courseId = courses.save(Course.create("EECS3311", "", null)).getId();
        materials.save(newMaterial(courseId, "h", new Chunk(0, 0, 0, 0, "text", 1)));

        List<Chunk> chunks = materials.findChunksByCourse(courseId);
        chunks.get(0).setEmbedding(new float[] {1f, 2f}, "gemini-embedding-001");
        materials.updateEmbeddings(chunks);

        assertTrue(materials.findChunksByCourse(courseId).get(0).hasEmbeddingFrom("gemini-embedding-001"));
    }

    @Test
    void duplicateHashInSameCourseIsRejectedAndNothingIsHalfSaved() {
        long courseId = courses.save(Course.create("EECS3311", "", null)).getId();
        materials.save(newMaterial(courseId, "same", new Chunk(0, 0, 0, 0, "a", 1)));

        assertThrows(PersistenceException.class,
                () -> materials.save(newMaterial(courseId, "same", new Chunk(0, 0, 0, 0, "b", 1))));
        assertEquals(1, materials.findChunksByCourse(courseId).size());
    }

    @Test
    void deletingCourseRemovesItsMaterials() {
        long courseId = courses.save(Course.create("EECS3311", "", null)).getId();
        materials.save(newMaterial(courseId, "h", new Chunk(0, 0, 0, 0, "a", 1)));

        courses.delete(courseId);

        assertTrue(materials.findByCourse(courseId).isEmpty());
        assertTrue(materials.findChunksByCourse(courseId).isEmpty());
    }
}
