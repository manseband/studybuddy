package studybuddy.domain;

/**
 * A searchable piece of a material's text (F01), up to about 400 words.
 * A chunk covers the pages pageNumber..endPage (one page or a few consecutive short pages), which
 * are used for citations.
 * The embedding is cached together with the name of the model that produced it, so a model change
 * can be detected and the chunk re-embedded.
 */
public class Chunk {

    private long id;
    private long materialId;
    private long courseId;
    private int position;
    private String text;
    private int pageNumber;
    private int endPage;
    private float[] embedding;
    private String embeddingModel;

    /** A chunk from a single page. */
    public Chunk(long id, long materialId, long courseId, int position, String text, int pageNumber) {
        this(id, materialId, courseId, position, text, pageNumber, pageNumber);
    }

    /** A chunk covering pages pageNumber..endPage. */
    public Chunk(long id, long materialId, long courseId, int position, String text, int pageNumber, int endPage) {
        if (text == null || text.isBlank()) {
            throw new IllegalArgumentException("Chunk text must not be empty");
        }
        if (pageNumber < 1 || endPage < pageNumber) {
            throw new IllegalArgumentException("Page numbers start at 1 and the end page cannot come first");
        }
        this.id = id;
        this.materialId = materialId;
        this.courseId = courseId;
        this.position = position;
        this.text = text;
        this.pageNumber = pageNumber;
        this.endPage = endPage;
    }

    /** Last page this chunk covers (equal to {@link #getPageNumber()} for a single-page chunk). */
    public int getEndPage() {
        return endPage;
    }

    /** "p. 3" or "pp. 3-5". */
    public String pageLabel() {
        return pageLabel(pageNumber, endPage);
    }

    public static String pageLabel(int from, int to) {
        return to > from ? "pp. " + from + "-" + to : "p. " + from;
    }

    public long getId() {
        return id;
    }

    public void setId(long id) {
        this.id = id;
    }

    public long getMaterialId() {
        return materialId;
    }

    public void setMaterialId(long materialId) {
        this.materialId = materialId;
    }

    public long getCourseId() {
        return courseId;
    }

    public void setCourseId(long courseId) {
        this.courseId = courseId;
    }

    /** Order of the chunk within its material, starting at 0. */
    public int getPosition() {
        return position;
    }

    public String getText() {
        return text;
    }

    public int getPageNumber() {
        return pageNumber;
    }

    public int wordCount() {
        return text.isBlank() ? 0 : text.trim().split("\\s+").length;
    }

    public float[] getEmbedding() {
        return embedding;
    }

    public String getEmbeddingModel() {
        return embeddingModel;
    }

    public void setEmbedding(float[] embedding, String model) {
        this.embedding = embedding;
        this.embeddingModel = model;
    }

    /** True if the chunk has a cached vector from the given embedding model. */
    public boolean hasEmbeddingFrom(String model) {
        return embedding != null && model != null && model.equals(embeddingModel);
    }
}
