package studybuddy.ai;

/** A material file could not be read (encrypted, corrupted or unreadable). Nothing should be saved. */
public class DocumentParseException extends RuntimeException {

    public DocumentParseException(String message, Throwable cause) {
        super(message, cause);
    }

    public DocumentParseException(String message) {
        super(message);
    }
}
