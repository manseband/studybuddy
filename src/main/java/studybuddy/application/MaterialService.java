package studybuddy.application;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.NoSuchElementException;

import studybuddy.ai.DocumentParser;
import studybuddy.ai.RetrievalStrategy;
import studybuddy.domain.Chunk;
import studybuddy.domain.Course;
import studybuddy.domain.Material;
import studybuddy.domain.MaterialStatus;
import studybuddy.persistence.CourseRepository;
import studybuddy.persistence.MaterialRepository;

/**
 * Course and material management (F01). Ingestion workflow for an upload:
 * validate (type, size, duplicate hash) -> extract pages -> split into overlapping chunks with page
 * references -> save material and chunks in one transaction -> index the chunks for search ->
 * notify observers.
 */
public class MaterialService {

    private final CourseRepository courseRepo;
    private final MaterialRepository materialRepo;
    private final DocumentParser parser;
    private final RetrievalStrategy retriever;
    private final EventBus eventBus;
    private final long maxFileBytes;
    private final int chunkWords;
    private final int chunkOverlap;

    public MaterialService(CourseRepository courseRepo, MaterialRepository materialRepo, DocumentParser parser,
                           RetrievalStrategy retriever, EventBus eventBus, long maxFileBytes, int chunkWords,
                           int chunkOverlap) {
        if (chunkOverlap < 0 || chunkOverlap >= chunkWords) {
            throw new IllegalArgumentException("Chunk overlap must be between 0 and the chunk size");
        }
        this.courseRepo = courseRepo;
        this.materialRepo = materialRepo;
        this.parser = parser;
        this.retriever = retriever;
        this.eventBus = eventBus;
        this.maxFileBytes = maxFileBytes;
        this.chunkWords = chunkWords;
        this.chunkOverlap = chunkOverlap;
    }

    // ----- courses -----

    public Course createCourse(String name, String code, LocalDate examDate) {
        Course course = Course.create(name, code, examDate);
        if (courseRepo.findByName(course.getName()).isPresent()) {
            throw new IllegalArgumentException("A course named \"" + course.getName() + "\" already exists");
        }
        courseRepo.save(course);
        eventBus.publish(StudyEvent.of(StudyEvent.Type.COURSE_CHANGED, course.getId(),
                "Created course " + course.getName()));
        return course;
    }

    public List<Course> listCourses() {
        return courseRepo.findAll();
    }

    public Course getCourse(long courseId) {
        return courseRepo.findById(courseId)
                .orElseThrow(() -> new NoSuchElementException("No course with id " + courseId));
    }

    /** Finds a course by id ("3") or by name ("EECS3311"), as typed on the CLI. */
    public Course findCourse(String idOrName) {
        String key = idOrName.trim();
        if (key.matches("\\d+")) {
            var byId = courseRepo.findById(Long.parseLong(key));
            if (byId.isPresent()) {
                return byId.get();
            }
        }
        return courseRepo.findByName(key)
                .orElseThrow(() -> new NoSuchElementException("No course named or numbered \"" + key + "\""));
    }

    /** Deletes the course with all of its materials, quizzes, cards and plans. */
    public void deleteCourse(long courseId) {
        Course course = getCourse(courseId);
        for (Material material : materialRepo.findByCourse(courseId)) {
            retriever.removeMaterial(material.getId());
        }
        courseRepo.delete(courseId);
        eventBus.publish(StudyEvent.of(StudyEvent.Type.COURSE_CHANGED, courseId, "Deleted course " + course.getName()));
    }

    // ----- materials -----

    /** Uploads a material; a duplicate of an existing file is rejected. */
    public Material uploadMaterial(long courseId, Path file) {
        return uploadMaterial(courseId, file, false);
    }

    /**
     * Uploads a material.
     *
     * @param replaceDuplicate if the course already has a file with the same content, remove it first
     *                         instead of throwing {@link DuplicateMaterialException}
     * @throws IllegalArgumentException                for a missing, unsupported or too large file
     * @throws studybuddy.ai.DocumentParseException     for an encrypted or corrupted PDF (nothing is saved)
     */
    public Material uploadMaterial(long courseId, Path file, boolean replaceDuplicate) {
        getCourse(courseId);
        validateFile(file);
        String hash = sha256(file);
        var duplicate = materialRepo.findByHash(courseId, hash);
        if (duplicate.isPresent()) {
            if (!replaceDuplicate) {
                throw new DuplicateMaterialException(duplicate.get());
            }
            removeMaterial(duplicate.get().getId());
        }

        List<String> pages = parser.extractPages(file);
        List<Chunk> chunks = splitIntoChunks(pages);
        int words = pages.stream().mapToInt(MaterialService::countWords).sum();
        MaterialStatus status = chunks.isEmpty() ? MaterialStatus.NO_TEXT : MaterialStatus.INDEXED;

        Material material = new Material(0, courseId, file.getFileName().toString(), DocumentParser.fileType(file),
                status, hash, pages.size(), words, LocalDateTime.now());
        chunks.forEach(c -> c.setCourseId(courseId));
        material.setChunks(chunks);
        materialRepo.save(material);

        if (!chunks.isEmpty()) {
            retriever.index(material.getChunks());
            saveNewEmbeddings(material.getChunks());
        }
        eventBus.publish(StudyEvent.of(StudyEvent.Type.MATERIAL_ADDED, courseId, "Added material " + material.getTitle()));
        return material;
    }

    public List<Material> listMaterials(long courseId) {
        getCourse(courseId);
        return materialRepo.findByCourse(courseId);
    }

    public Material getMaterial(long materialId) {
        return materialRepo.findById(materialId)
                .orElseThrow(() -> new NoSuchElementException("No material with id " + materialId));
    }

    public Material renameMaterial(long materialId, String newTitle) {
        Material material = getMaterial(materialId);
        material.setTitle(newTitle);
        materialRepo.save(material);
        eventBus.publish(StudyEvent.of(StudyEvent.Type.MATERIAL_ADDED, material.getCourseId(),
                "Renamed material to " + material.getTitle()));
        return material;
    }

    public void removeMaterial(long materialId) {
        Material material = getMaterial(materialId);
        retriever.removeMaterial(materialId);
        materialRepo.delete(materialId);
        eventBus.publish(StudyEvent.of(StudyEvent.Type.MATERIAL_REMOVED, material.getCourseId(),
                "Removed material " + material.getTitle()));
    }

    /**
     * Rebuilds the in-memory search index from the database at startup. No API calls are made here;
     * chunks whose embeddings failed earlier are retried by {@link #refreshEmbeddings}.
     */
    public void loadIndex() {
        for (Course course : courseRepo.findAll()) {
            List<Chunk> chunks = materialRepo.findChunksByCourse(course.getId());
            if (!chunks.isEmpty()) {
                retriever.load(chunks);
            }
        }
    }

    /** Result of {@link #refreshEmbeddings}: how many chunks had no embedding before and after. */
    public record EmbeddingRefresh(int chunks, int missingBefore, int missingAfter) {

        public int created() {
            return missingBefore - missingAfter;
        }
    }

    /**
     * Requests embeddings again for the course's chunks that do not have one yet, e.g. after the
     * embedding service was unavailable or rate-limited during upload (F01). Without an API key nothing
     * changes and search stays keyword-based.
     */
    public EmbeddingRefresh refreshEmbeddings(long courseId) {
        getCourse(courseId);
        List<Chunk> chunks = materialRepo.findChunksByCourse(courseId);
        List<Chunk> missing = chunks.stream().filter(c -> c.getEmbedding() == null).toList();
        if (!missing.isEmpty()) {
            retriever.index(missing);
            saveNewEmbeddings(missing);
        }
        int stillMissing = (int) missing.stream().filter(c -> c.getEmbedding() == null).count();
        return new EmbeddingRefresh(chunks.size(), missing.size(), stillMissing);
    }

    /**
     * Splits the pages into chunks of at most chunkWords words, keeping exact page ranges for citations:
     * <ul>
     *   <li>consecutive whole pages are grouped while they fit (e.g. several short slides in one chunk);</li>
     *   <li>a page longer than chunkWords is split on its own into chunks that overlap by chunkOverlap words.</li>
     * </ul>
     * A chunk never contains part of a page together with another page, so its page range is exact.
     */
    private List<Chunk> splitIntoChunks(List<String> pages) {
        List<Chunk> chunks = new ArrayList<>();
        List<String> group = new ArrayList<>();
        int groupStart = 0;
        int groupEnd = 0;
        for (int p = 1; p <= pages.size(); p++) {
            String pageText = pages.get(p - 1).strip();
            if (pageText.isEmpty()) {
                continue;
            }
            List<String> words = List.of(pageText.split("\\s+"));
            if (words.size() > chunkWords) {
                addChunk(chunks, group, groupStart, groupEnd);
                group.clear();
                splitLongPage(chunks, words, p);
                continue;
            }
            if (group.size() + words.size() > chunkWords) {
                addChunk(chunks, group, groupStart, groupEnd);
                group.clear();
            }
            if (group.isEmpty()) {
                groupStart = p;
            }
            group.addAll(words);
            groupEnd = p;
        }
        addChunk(chunks, group, groupStart, groupEnd);
        return chunks;
    }

    /** Splits one long page into overlapping windows of chunkWords words. */
    private void splitLongPage(List<Chunk> chunks, List<String> words, int page) {
        int step = chunkWords - chunkOverlap;
        for (int start = 0; start < words.size(); start += step) {
            int end = Math.min(start + chunkWords, words.size());
            addChunk(chunks, words.subList(start, end), page, page);
            if (end == words.size()) {
                break;
            }
        }
    }

    private static void addChunk(List<Chunk> chunks, List<String> words, int fromPage, int toPage) {
        if (!words.isEmpty()) {
            chunks.add(new Chunk(0, 0, 0, chunks.size(), String.join(" ", words), fromPage, toPage));
        }
    }

    /** Persists vectors the retriever just computed, so they are not requested again. */
    private void saveNewEmbeddings(List<Chunk> chunks) {
        List<Chunk> embedded = chunks.stream().filter(c -> c.getEmbedding() != null).toList();
        if (!embedded.isEmpty()) {
            materialRepo.updateEmbeddings(embedded);
        }
    }

    private void validateFile(Path file) {
        if (!Files.isRegularFile(file)) {
            throw new IllegalArgumentException("File not found: " + file);
        }
        if (!parser.isSupported(file)) {
            throw new IllegalArgumentException("Unsupported file type. Accepted types: PDF, TXT, MD (up to "
                    + maxFileBytes / (1024 * 1024) + " MB)");
        }
        try {
            if (Files.size(file) > maxFileBytes) {
                throw new IllegalArgumentException("File is too large. The limit is "
                        + maxFileBytes / (1024 * 1024) + " MB (accepted types: PDF, TXT, MD)");
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Could not read " + file, e);
        }
    }

    private static String sha256(Path file) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(Files.readAllBytes(file)));
        } catch (IOException e) {
            throw new UncheckedIOException("Could not read " + file, e);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }

    private static int countWords(String text) {
        return text.isBlank() ? 0 : text.trim().split("\\s+").length;
    }
}
