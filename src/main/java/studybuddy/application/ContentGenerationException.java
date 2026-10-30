package studybuddy.application;

import java.util.List;

import studybuddy.domain.Citation;

/**
 * An AI feature could not produce its result (model unreachable after retries, or still malformed after
 * the repair request). Carries the excerpts that were retrieved, so the student can still read them (F04).
 */
public class ContentGenerationException extends RuntimeException {

    private final transient List<Citation> excerpts;

    public ContentGenerationException(String message, List<Citation> excerpts, Throwable cause) {
        super(message, cause);
        this.excerpts = List.copyOf(excerpts);
    }

    public List<Citation> getExcerpts() {
        return excerpts;
    }
}
