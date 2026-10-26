package studybuddy.persistence;

/** Unchecked wrapper for database errors, so services do not have to handle SQLException. */
public class PersistenceException extends RuntimeException {

    public PersistenceException(String message, Throwable cause) {
        super(message, cause);
    }

    public PersistenceException(String message) {
        super(message);
    }
}
