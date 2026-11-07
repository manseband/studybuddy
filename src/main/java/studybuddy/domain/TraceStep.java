package studybuddy.domain;

import java.util.Map;

/**
 * One tool call made by the Study Coach (F09).
 *
 * @param number      position in the conversation turn, starting at 1
 * @param description what the step does, for the student, e.g. "Generating 8-question quiz"
 * @param result      short summary of the result, or the error; empty while running
 * @param millis      how long the tool took
 */
public record TraceStep(int number, String tool, String description, Map<String, Object> arguments, String result,
                        Status status, long millis) {

    public enum Status {
        RUNNING,
        SUCCEEDED,
        FAILED
    }
}
