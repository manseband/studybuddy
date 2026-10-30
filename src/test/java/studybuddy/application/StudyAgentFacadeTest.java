package studybuddy.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import studybuddy.ai.KeywordRetriever;
import studybuddy.config.AppConfig;
import studybuddy.domain.Course;
import studybuddy.domain.Material;

/** End-to-end F01 through the facade, wired by ApplicationFactory without an API key. */
class StudyAgentFacadeTest {

    @TempDir
    Path dir;

    private AppConfig config() {
        return AppConfig.load(dir.resolve("no.env"), Map.of("STUDYBUDDY_DB_PATH", dir.resolve("db/test.db").toString()));
    }

    @Test
    void createsCourseUploadsMaterialAndKeepsItAfterRestart() throws IOException {
        Path notes = Files.writeString(dir.resolve("notes.md"), "# Facade\nA facade offers one simple interface.");
        List<StudyEvent> events = new ArrayList<>();

        try (StudyAgentFacade facade = ApplicationFactory.create(config())) {
            assertFalse(facade.isAiAvailable());
            facade.addListener(events::add);
            Course course = facade.createCourse("EECS3311", "Software Design", LocalDate.of(2026, 12, 10));
            Material material = facade.uploadMaterial(course.getId(), notes);
            assertEquals(1, material.getChunkCount());
        }
        assertEquals(List.of(StudyEvent.Type.COURSE_CHANGED, StudyEvent.Type.MATERIAL_ADDED),
                events.stream().map(StudyEvent::type).toList());

        try (StudyAgentFacade reopened = ApplicationFactory.create(config())) {
            Course course = reopened.findCourse("eecs3311");
            assertEquals(LocalDate.of(2026, 12, 10), course.getExamDate());
            assertEquals("notes.md", reopened.listMaterials(course.getId()).get(0).getTitle());
        }
        assertTrue(Files.exists(dir.resolve("db/test.db")));
    }

    @Test
    void usesKeywordSearchWithoutAKey() {
        assertInstanceOf(KeywordRetriever.class, ApplicationFactory.createRetriever(config()));
    }
}
