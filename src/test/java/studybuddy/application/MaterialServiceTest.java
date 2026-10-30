package studybuddy.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.output.Response;
import studybuddy.ai.DocumentParseException;
import studybuddy.ai.DocumentParser;
import studybuddy.ai.EmbeddingRetriever;
import studybuddy.ai.KeywordRetriever;
import studybuddy.domain.Chunk;
import studybuddy.domain.Course;
import studybuddy.domain.Material;
import studybuddy.domain.MaterialStatus;
import studybuddy.persistence.CourseRepository;
import studybuddy.persistence.Database;
import studybuddy.persistence.MaterialRepository;

class MaterialServiceTest {

    @TempDir
    Path dir;

    private Database db;
    private KeywordRetriever retriever;
    private List<StudyEvent> events;
    private MaterialService service;
    private long courseId;

    @BeforeAll
    static void quietLogs() {
        java.util.logging.Logger.getLogger(EmbeddingRetriever.class.getName()).setLevel(java.util.logging.Level.OFF);
    }

    @BeforeEach
    void setUp() {
        db = Database.inMemory();
        retriever = new KeywordRetriever();
        events = new ArrayList<>();
        EventBus bus = new EventBus();
        bus.subscribe(events::add);
        // small chunks (10 words, 3 overlap) keep the test files short
        service = new MaterialService(new CourseRepository(db), new MaterialRepository(db), new DocumentParser(),
                retriever, bus, 1024, 10, 3);
        courseId = service.createCourse("EECS3311", "Software Design", null).getId();
    }

    @AfterEach
    void tearDown() {
        db.close();
    }

    /** "w1 w2 ... wN" so chunk boundaries are easy to check. */
    private static String words(int from, int to) {
        return IntStream.rangeClosed(from, to).mapToObj(i -> "w" + i).collect(Collectors.joining(" "));
    }

    @Test
    void uploadSplitsEachPageIntoOverlappingChunks() throws IOException {
        // page 1: w1..w12, page 2: w13..w20
        Path file = Files.writeString(dir.resolve("notes.txt"), words(1, 12) + "\f" + words(13, 20));

        Material material = service.uploadMaterial(courseId, file);

        assertEquals(MaterialStatus.INDEXED, material.getStatus());
        assertEquals(2, material.getPageCount());
        assertEquals(20, material.getWordCount());
        // page 1 (12 words) -> w1..w10 and w8..w12 (3-word overlap); page 2 starts a new chunk
        List<Chunk> chunks = material.getChunks();
        assertEquals(3, chunks.size());
        assertEquals(words(1, 10), chunks.get(0).getText());
        assertEquals(words(8, 12), chunks.get(1).getText());
        assertEquals(1, chunks.get(1).getPageNumber());
        assertEquals(words(13, 20), chunks.get(2).getText());
        assertEquals(2, chunks.get(2).getPageNumber());
        assertTrue(chunks.stream().allMatch(c -> c.getId() > 0 && c.getCourseId() == courseId));
        assertEquals(3, retriever.size());
        assertEquals(StudyEvent.Type.MATERIAL_ADDED, events.get(events.size() - 1).type());
    }

    @Test
    void shortPagesAreGroupedWithAPageRange() throws IOException {
        // pages of 3, 4, 2 and 6 words with 10-word chunks: pages 1-3 fit together (9 words), page 4 does not
        Path slides = Files.writeString(dir.resolve("slides.txt"),
                words(1, 3) + "\f" + words(4, 7) + "\f\f" + words(8, 9) + "\f" + words(10, 15));

        List<Chunk> chunks = service.uploadMaterial(courseId, slides).getChunks();

        assertEquals(2, chunks.size());
        assertEquals(words(1, 9), chunks.get(0).getText());
        assertEquals(1, chunks.get(0).getPageNumber());
        assertEquals(4, chunks.get(0).getEndPage(), "the empty page 3 is inside the range");
        assertEquals("pp. 1-4", chunks.get(0).pageLabel());
        assertEquals(words(10, 15), chunks.get(1).getText());
        assertEquals("p. 5", chunks.get(1).pageLabel());
        assertEquals(5, new MaterialRepository(db).findChunksByCourse(courseId).get(1).getEndPage());
    }

    @Test
    void refreshEmbeddingsRetriesChunksThatFailedDuringUpload() throws IOException {
        ToggleEmbeddingModel model = new ToggleEmbeddingModel();
        model.failing = true;
        EmbeddingRetriever embeddings = new EmbeddingRetriever(model, "toy", 0.1);
        MaterialService withEmbeddings = new MaterialService(new CourseRepository(db), new MaterialRepository(db),
                new DocumentParser(), embeddings, new EventBus(), 1024, 10, 3);
        withEmbeddings.uploadMaterial(courseId, Files.writeString(dir.resolve("a.txt"), "Facade pattern"));
        assertEquals(new MaterialService.EmbeddingRefresh(1, 1, 1), withEmbeddings.refreshEmbeddings(courseId));

        model.failing = false;
        MaterialService.EmbeddingRefresh result = withEmbeddings.refreshEmbeddings(courseId);

        assertEquals(1, result.created());
        assertTrue(new MaterialRepository(db).findChunksByCourse(courseId).get(0).hasEmbeddingFrom("toy"));
        assertEquals(0, withEmbeddings.refreshEmbeddings(courseId).missingBefore());
    }

    @Test
    void startupLoadMakesNoEmbeddingCalls() throws IOException {
        service.uploadMaterial(courseId, Files.writeString(dir.resolve("a.txt"), "Facade pattern"));
        ToggleEmbeddingModel model = new ToggleEmbeddingModel();
        MaterialService restarted = new MaterialService(new CourseRepository(db), new MaterialRepository(db),
                new DocumentParser(), new EmbeddingRetriever(model, "toy", 0.1), new EventBus(), 1024, 10, 3);

        restarted.loadIndex();

        assertEquals(0, model.calls, "chunks without embeddings are not re-embedded at startup");
    }

    /** Embedding model stand-in: one dimension, can be switched to fail like a rate-limited service. */
    static class ToggleEmbeddingModel implements EmbeddingModel {
        boolean failing;
        int calls;

        @Override
        public Response<List<Embedding>> embedAll(List<TextSegment> segments) {
            calls++;
            if (failing) {
                throw new RuntimeException("rate limited");
            }
            return Response.from(segments.stream().map(s -> Embedding.from(new float[] {1f})).toList());
        }
    }

    @Test
    void uploadedTextIsSearchable() throws IOException {
        Path file = Files.writeString(dir.resolve("patterns.md"), "The Observer pattern notifies subscribers.");
        service.uploadMaterial(courseId, file);

        assertEquals(1, retriever.retrieve(courseId, "observer", 6).size());
    }

    @Test
    void rejectsUnsupportedTooLargeAndMissingFiles() throws IOException {
        Path pptx = Files.writeString(dir.resolve("slides.pptx"), "x");
        Path big = Files.writeString(dir.resolve("big.txt"), "x".repeat(2000));

        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> service.uploadMaterial(courseId, pptx));
        assertTrue(e.getMessage().contains("PDF, TXT, MD"));
        assertThrows(IllegalArgumentException.class, () -> service.uploadMaterial(courseId, big));
        assertThrows(IllegalArgumentException.class, () -> service.uploadMaterial(courseId, dir.resolve("none.txt")));
        assertTrue(service.listMaterials(courseId).isEmpty());
    }

    @Test
    void duplicateIsRejectedUnlessReplaced() throws IOException {
        Path file = Files.writeString(dir.resolve("a.txt"), "Facade pattern");
        Path copy = Files.writeString(dir.resolve("copy.txt"), "Facade pattern");
        Material first = service.uploadMaterial(courseId, file);

        DuplicateMaterialException e = assertThrows(DuplicateMaterialException.class,
                () -> service.uploadMaterial(courseId, copy));
        assertEquals(first.getId(), e.getExisting().getId());

        Material replaced = service.uploadMaterial(courseId, copy, true);
        assertEquals(List.of(replaced.getId()), service.listMaterials(courseId).stream().map(Material::getId).toList());
        assertEquals("copy.txt", replaced.getTitle());
    }

    @Test
    void fileWithoutTextIsSavedButNotIndexed() throws IOException {
        Path empty = Files.writeString(dir.resolve("scan.txt"), "   \f  ");

        Material material = service.uploadMaterial(courseId, empty);

        assertEquals(MaterialStatus.NO_TEXT, material.getStatus());
        assertEquals(0, material.getChunkCount());
        assertEquals(0, retriever.size());
    }

    @Test
    void corruptedPdfSavesNothing() throws IOException {
        Path broken = Files.writeString(dir.resolve("broken.pdf"), "not a pdf");

        assertThrows(DocumentParseException.class, () -> service.uploadMaterial(courseId, broken));
        assertTrue(service.listMaterials(courseId).isEmpty());
    }

    @Test
    void duplicateCourseNamesAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> service.createCourse("eecs3311", "", null));
        assertEquals("EECS3311", service.findCourse("eecs3311").getName());
        assertEquals(courseId, service.findCourse(String.valueOf(courseId)).getId());
    }

    @Test
    void removingMaterialsAndCoursesClearsTheIndex() throws IOException {
        Material m = service.uploadMaterial(courseId, Files.writeString(dir.resolve("a.txt"), "Strategy pattern"));
        service.renameMaterial(m.getId(), "Week 3 notes");
        assertEquals("Week 3 notes", service.getMaterial(m.getId()).getTitle());

        service.removeMaterial(m.getId());
        assertEquals(0, retriever.size());

        service.uploadMaterial(courseId, Files.writeString(dir.resolve("b.txt"), "Adapter pattern"));
        service.deleteCourse(courseId);
        assertEquals(0, retriever.size());
        assertTrue(service.listCourses().isEmpty());
    }

    @Test
    void indexIsRebuiltFromTheDatabase() throws IOException {
        service.uploadMaterial(courseId, Files.writeString(dir.resolve("a.txt"), "Builder pattern steps"));
        KeywordRetriever fresh = new KeywordRetriever();
        MaterialService restarted = new MaterialService(new CourseRepository(db), new MaterialRepository(db),
                new DocumentParser(), fresh, new EventBus(), 1024, 10, 3);

        restarted.loadIndex();

        assertEquals(1, fresh.retrieve(courseId, "builder", 6).size());
        Course course = restarted.getCourse(courseId);
        assertEquals("Software Design", course.getCode());
    }
}
