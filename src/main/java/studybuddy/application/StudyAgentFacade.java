package studybuddy.application;

import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;

import studybuddy.domain.Course;
import studybuddy.domain.Material;

/**
 * Facade: the single entry point used by the GUI and the CLI. It hides the services, repositories,
 * AI clients and retrievers behind a small set of use-case methods, so the presentation layer never
 * depends on them directly. Services for later features are added here as they are implemented.
 */
public class StudyAgentFacade implements AutoCloseable {

    private final MaterialService materialService;
    private final EventBus eventBus;
    private final boolean aiAvailable;
    private final AutoCloseable resources;

    /**
     * @param aiAvailable true when a Gemini API key is configured
     * @param resources   closed by {@link #close()} (the database)
     */
    public StudyAgentFacade(MaterialService materialService, EventBus eventBus, boolean aiAvailable,
                            AutoCloseable resources) {
        this.materialService = materialService;
        this.eventBus = eventBus;
        this.aiAvailable = aiAvailable;
        this.resources = resources;
    }

    // ----- F01 courses and materials -----

    public Course createCourse(String name, String code, LocalDate examDate) {
        return materialService.createCourse(name, code, examDate);
    }

    public List<Course> listCourses() {
        return materialService.listCourses();
    }

    public Course findCourse(String idOrName) {
        return materialService.findCourse(idOrName);
    }

    public void deleteCourse(long courseId) {
        materialService.deleteCourse(courseId);
    }

    public Material uploadMaterial(long courseId, Path file) {
        return materialService.uploadMaterial(courseId, file);
    }

    public Material uploadMaterial(long courseId, Path file, boolean replaceDuplicate) {
        return materialService.uploadMaterial(courseId, file, replaceDuplicate);
    }

    public List<Material> listMaterials(long courseId) {
        return materialService.listMaterials(courseId);
    }

    public Material renameMaterial(long materialId, String newTitle) {
        return materialService.renameMaterial(materialId, newTitle);
    }

    public void removeMaterial(long materialId) {
        materialService.removeMaterial(materialId);
    }

    // ----- general -----

    public void addListener(StudyEventListener listener) {
        eventBus.subscribe(listener);
    }

    /** False when no API key is configured; AI features then explain how to add one. */
    public boolean isAiAvailable() {
        return aiAvailable;
    }

    @Override
    public void close() {
        try {
            resources.close();
        } catch (Exception e) {
            throw new IllegalStateException("Could not close StudyBuddy cleanly", e);
        }
    }
}
