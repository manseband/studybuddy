package studybuddy.domain;

/**
 * A reference from generated text back to a course excerpt.
 * excerptNumber is the 1-based number the excerpt had in the prompt; page..endPage are the pages
 * the excerpt covers.
 */
public record Citation(int excerptNumber, long chunkId, long materialId, String materialTitle, int page, int endPage,
                       String excerpt) {

    /** A citation of a single-page excerpt. */
    public Citation(int excerptNumber, long chunkId, long materialId, String materialTitle, int page, String excerpt) {
        this(excerptNumber, chunkId, materialId, materialTitle, page, page, excerpt);
    }

    /** "p. 3" or "pp. 3-5". */
    public String pageLabel() {
        return Chunk.pageLabel(page, endPage);
    }
}
