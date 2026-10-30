package studybuddy.ai;

/**
 * The model could not be reached or did not answer usefully.
 * retryable is true for timeouts, network errors and rate limits; false for e.g. an invalid API key.
 */
public class LLMException extends RuntimeException {

    private final boolean retryable;

    public LLMException(String message, boolean retryable, Throwable cause) {
        super(message, cause);
        this.retryable = retryable;
    }

    public LLMException(String message, boolean retryable) {
        this(message, retryable, null);
    }

    public boolean isRetryable() {
        return retryable;
    }
}
