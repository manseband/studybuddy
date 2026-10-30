package studybuddy.ai;

/** The model's reply was not valid JSON in the expected format. */
public class ResponseFormatException extends RuntimeException {

    public ResponseFormatException(String message, Throwable cause) {
        super(message, cause);
    }

    public ResponseFormatException(String message) {
        super(message);
    }
}
