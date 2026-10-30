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
     * Rebuilds the in-memory search index from the database at startup. Chunks that are missing
     * embeddings get them now if the retriever can create them (F01: "requested again later").
     */
    public void loadIndex() {
        for (Course course : courseRepo.findAll()) {
            List<Chunk> chunks = materialRepo.findChunksByCourse(course.getId());
            if (!chunks.isEmpty()) {
                retriever.index(chunks);
                saveNewEmbeddings(chunks);
            }
        }
    }

    /**
     * Splits page texts into chunks of about chunkWords words, each overlapping the previous one by
     * chunkOverlap words. A chunk's page number is the page its first word comes from.
     */
    private List<Chunk> splitIntoChunks(List<String> pages) {
        List<String> words = new ArrayList<>();
        List<Integer> wordPages = new ArrayList<>();
        for (int p = 0; p < pages.size(); p++) {
            for (String word : pages.get(p).split("\\s+")) {
                if (!word.isEmpty()) {
                    words.add(word);
                    wordPages.add(p + 1);
                }
            }
        }
        List<Chunk> chunks = new ArrayList<>();
        int step = chunkWords - chunkOverlap;
        for (int start = 0; start < words.size(); start += step) {
            int end = Math.min(start + chunkWords, words.size());
            String text = String.join(" ", words.subList(start, end));
            chunks.add(new Chunk(0, 0, 0, chunks.size(), text, wordPages.get(start)));
            if (end == words.size()) {
                break;
            }
        }
        return chunks;
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
