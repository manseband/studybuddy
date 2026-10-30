package studybuddy.application;

import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;

import studybuddy.domain.Answer;
import studybuddy.domain.Concept;
import studybuddy.domain.Course;
import studybuddy.domain.Material;
import studybuddy.domain.QaEntry;

/**
 * Facade: the single entry point used by the GUI and the CLI. It hides the services, repositories,
 * AI clients and retrievers behind a small set of use-case methods, so the presentation layer never
 * depends on them directly. Services for later features are added here as they are implemented.
 */
public class StudyAgentFacade implements AutoCloseable {

    private final MaterialService materialService;
    private final LearningContentService contentService;
    private final EventBus eventBus;
    private final boolean aiAvailable;
    private final AutoCloseable resources;

    /**
     * @param aiAvailable true when a Gemini API key is configured
     * @param resources   closed by {@link #close()} (the database)
     */
    public StudyAgentFacade(MaterialService materialService, LearningContentService contentService, EventBus eventBus,
                            boolean aiAvailable, AutoCloseable resources) {
        this.materialService = materialService;
        this.contentService = contentService;
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

    /** Retries embeddings for chunks that have none (e.g. after a rate limit during upload). */
    public MaterialService.EmbeddingRefresh refreshEmbeddings(long courseId) {
        return materialService.refreshEmbeddings(courseId);
    }

    // ----- F04 questions -----

    public Answer askQuestion(long courseId, String question) {
        return contentService.answerQuestion(courseId, question);
    }

    public Answer askQuestion(long courseId, String question, boolean allowGeneralKnowledge) {
        return contentService.answerQuestion(courseId, question, allowGeneralKnowledge);
    }

    public List<QaEntry> getQuestionHistory(long courseId) {
        return contentService.getQuestionHistory(courseId);
    }

    // ----- F03 concepts -----

    /** Candidate concepts for review; nothing is saved until {@link #saveConcepts}. */
    public List<Concept> extractConcepts(long materialId) {
        return contentService.extractConcepts(materialId);
    }

    public List<Concept> saveConcepts(long materialId, List<Concept> concepts) {
        return contentService.saveConcepts(materialId, concepts);
    }

    public List<Concept> listConcepts(long courseId) {
        return contentService.listConcepts(courseId);
    }

    public Concept getConcept(long conceptId) {
        return contentService.getConcept(conceptId);
    }

    public List<Material> getConceptMaterials(long conceptId) {
        return contentService.getConceptMaterials(conceptId);
    }

    public Concept updateConcept(long conceptId, String name, String definition, Integer importance) {
        return contentService.updateConcept(conceptId, name, definition, importance);
    }

    public Concept mergeConcepts(long fromId, long intoId) {
        return contentService.mergeConcepts(fromId, intoId);
    }

    public void deleteConcept(long conceptId) {
        contentService.deleteConcept(conceptId);
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
