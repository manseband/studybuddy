package studybuddy.domain;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** An uploaded learning material (F01) and the chunks extracted from it. */
public class Material {

    private long id;
    private long courseId;
    private String title;
    private String fileType;
    private MaterialStatus status;
    private String contentHash;
    private int pageCount;
    private int wordCount;
    private LocalDateTime addedAt;
    private final List<Chunk> chunks = new ArrayList<>();

    public Material(long id, long courseId, String title, String fileType, MaterialStatus status,
                    String contentHash, int pageCount, int wordCount, LocalDateTime addedAt) {
        this.id = id;
        this.courseId = courseId;
        setTitle(title);
        this.fileType = fileType.toLowerCase(Locale.ROOT);
        this.status = status;
        this.contentHash = contentHash;
        this.pageCount = pageCount;
        this.wordCount = wordCount;
        this.addedAt = addedAt;
    }

    public long getId() {
        return id;
    }

    public void setId(long id) {
        this.id = id;
        chunks.forEach(chunk -> chunk.setMaterialId(id));
    }

    public long getCourseId() {
        return courseId;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        if (title == null || title.isBlank()) {
            throw new IllegalArgumentException("Material title is required");
        }
        this.title = title.trim();
    }

    /** "pdf", "txt" or "md". */
    public String getFileType() {
        return fileType;
    }

    public MaterialStatus getStatus() {
        return status;
    }

    public void setStatus(MaterialStatus status) {
        this.status = status;
    }

    /** SHA-256 of the file bytes, used to detect duplicate uploads within a course. */
    public String getContentHash() {
        return contentHash;
    }

    public int getPageCount() {
        return pageCount;
    }

    public int getWordCount() {
        return wordCount;
    }

    public LocalDateTime getAddedAt() {
        return addedAt;
    }

    public List<Chunk> getChunks() {
        return List.copyOf(chunks);
    }

    public int getChunkCount() {
        return chunks.size();
    }

    public void setChunks(List<Chunk> newChunks) {
        chunks.clear();
        chunks.addAll(newChunks);
    }

    public boolean hasText() {
        return status != MaterialStatus.NO_TEXT && !chunks.isEmpty();
    }

    /** Chunks starting inside the given page range (inclusive); null bounds mean open-ended. */
    public List<Chunk> chunksInPages(Integer fromPage, Integer toPage) {
        return chunks.stream()
                .filter(c -> fromPage == null || c.getPageNumber() >= fromPage)
                .filter(c -> toPage == null || c.getPageNumber() <= toPage)
                .toList();
    }

    @Override
    public String toString() {
        return title;
    }
}
