package studybuddy.domain;

/**
 * A reference from generated text back to a course excerpt.
 * excerptNumber is the 1-based number the excerpt had in the prompt.
 */
public record Citation(int excerptNumber, long chunkId, long materialId, String materialTitle, int page, String excerpt) {
}
