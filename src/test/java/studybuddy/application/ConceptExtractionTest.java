package studybuddy.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import studybuddy.ai.DocumentParser;
import studybuddy.ai.FakeLLMClient;
import studybuddy.ai.KeywordRetriever;
import studybuddy.ai.LLMException;
import studybuddy.ai.ResponseParser;
import studybuddy.domain.Concept;
import studybuddy.domain.Material;
import studybuddy.persistence.ConceptRepository;
import studybuddy.persistence.CourseRepository;
import studybuddy.persistence.Database;
import studybuddy.persistence.MaterialRepository;
import studybuddy.persistence.QaHistoryRepository;

/** F03 concept extraction with a scripted model. Each page is one 10-word chunk; the word budget is 15. */
class ConceptExtractionTest {

    @TempDir
    Path dir;

    private Database db;
    private FakeLLMClient llm;
    private LearningContentService service;
    private Material material;

    @BeforeEach
    void setUp() throws IOException {
        db = Database.inMemory();
        llm = new FakeLLMClient();
        MaterialService materials = new MaterialService(new CourseRepository(db), new MaterialRepository(db),
                new DocumentParser(), new KeywordRetriever(), new EventBus(), 1_000_000, 10, 0);
        long courseId = materials.createCourse("EECS3311", "", null).getId();
        String page = "word ".repeat(10).strip();
        material = materials.uploadMaterial(courseId, Files.writeString(dir.resolve("notes.txt"), page + "\f" + page));
        service = new LearningContentService(llm, new KeywordRetriever(), new ResponseParser(),
                new MaterialRepository(db), new QaHistoryRepository(db),
                new ConceptRepository(db), new EventBus(), 6, 15);
    }

    @AfterEach
    void tearDown() {
        db.close();
    }

    @Test
    void mergesDuplicatesAcrossGroupsAndKeepsKnownPrerequisites() {
        llm.enqueueText("""
                {"concepts": [
                  {"name": "Observer Pattern", "definition": "Notifies observers.", "pages": [1], "importance": 3,
                   "prerequisites": ["Interface", "Quantum physics"]},
                  {"name": "Interface", "definition": "A contract.", "pages": [1, 7], "importance": 2,
                   "prerequisites": []}]}""");
        llm.enqueueText("""
                {"concepts": [
                  {"name": "observer patterns", "definition": "", "pages": [2], "importance": 5,
                   "prerequisites": ["interfaces"]}]}""");

        List<Concept> candidates = service.extractConcepts(material.getId());

        assertEquals(2, llm.getRequests().size(), "two chunk groups");
        assertEquals(List.of("Observer Pattern", "Interface"), candidates.stream().map(Concept::getName).toList());
        Concept observer = candidates.get(0);
        assertEquals(5, observer.getImportance());
        assertEquals(List.of(1, 2), observer.getSourcePages());
        assertEquals("Notifies observers.", observer.getDefinition());
        assertEquals(List.of("Interface"), observer.getPrerequisites().stream().map(Concept::getName).toList());
        assertEquals(List.of(1), candidates.get(1).getSourcePages(), "page 7 does not exist and is dropped");
        assertTrue(service.listConcepts(material.getCourseId()).isEmpty(), "nothing is saved before review");
    }

    @Test
    void savedConceptsAreMatchedOnTheNextExtraction() {
        llm.enqueueText("{\"concepts\": [{\"name\": \"Facade\", \"definition\": \"Simple front.\", \"pages\": [1],"
                + " \"importance\": 4, \"prerequisites\": [\"Subsystem\"]},"
                + " {\"name\": \"Subsystem\", \"definition\": \"A group of classes.\", \"pages\": [1],"
                + " \"importance\": 2, \"prerequisites\": []}]}");
        llm.enqueueText("{\"concepts\": []}");
        List<Concept> saved = service.saveConcepts(material.getId(), service.extractConcepts(material.getId()));
        long facadeId = saved.get(0).getId();
        assertEquals("Subsystem", service.getConcept(facadeId).getPrerequisites().get(0).getName());
        assertEquals("notes.txt", service.getConceptMaterials(facadeId).get(0).getTitle());

        llm.enqueueText("{\"concepts\": [{\"name\": \"Facades\", \"definition\": \"x\", \"pages\": [2],"
                + " \"importance\": 3, \"prerequisites\": []}]}");
        llm.enqueueText("{\"concepts\": []}");
        List<Concept> again = service.extractConcepts(material.getId());

        assertEquals(facadeId, again.get(0).getId());
        assertEquals(List.of(1, 2), again.get(0).getSourcePages());
        assertEquals("Simple front.", again.get(0).getDefinition());
    }

    @Test
    void keepsAtMostFortyMostImportantConcepts() {
        StringBuilder json = new StringBuilder("{\"concepts\": [");
        for (int i = 0; i < 45; i++) {
            json.append(i == 0 ? "" : ",").append("{\"name\": \"Concept number ").append((char) ('A' + i % 26))
                .append(i).append("\", \"importance\": ").append(i < 5 ? 1 : 4).append("}");
        }
        llm.enqueueText(json.append("]}").toString()).enqueueText("{\"concepts\": []}");

        List<Concept> candidates = service.extractConcepts(material.getId());

        assertEquals(LearningContentService.MAX_CONCEPTS_PER_MATERIAL, candidates.size());
        assertTrue(candidates.stream().allMatch(c -> c.getImportance() == 4));
    }

    @Test
    void editMergeAndDelete() {
        llm.enqueueText("{\"concepts\": [{\"name\": \"Observer\", \"pages\": [1], \"importance\": 4},"
                + " {\"name\": \"Publish subscribe\", \"pages\": [2], \"importance\": 3},"
                + " {\"name\": \"Singleton\", \"pages\": [2], \"importance\": 2}]}");
        llm.enqueueText("{\"concepts\": []}");
        List<Concept> saved = service.saveConcepts(material.getId(), service.extractConcepts(material.getId()));
        long observer = saved.get(0).getId();
        long pubSub = saved.get(1).getId();
        long singleton = saved.get(2).getId();

        Concept edited = service.updateConcept(observer, null, "Subjects notify observers.", 5);
        assertEquals("Subjects notify observers.", edited.getDefinition());
        assertThrows(IllegalArgumentException.class, () -> service.updateConcept(singleton, "observers", null, null));

        Concept merged = service.mergeConcepts(pubSub, observer);
        assertEquals(List.of(1, 2), merged.getSourcePages());
        service.deleteConcept(singleton);

        assertEquals(List.of("Observer"),
                service.listConcepts(material.getCourseId()).stream().map(Concept::getName).toList());
    }

    @Test
    void failuresAreReported() {
        llm.enqueueFailure(new LLMException("timeout", true));
        assertThrows(ContentGenerationException.class, () -> service.extractConcepts(material.getId()));
    }
}
