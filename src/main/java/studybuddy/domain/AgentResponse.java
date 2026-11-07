package studybuddy.domain;

import java.util.List;

/**
 * The Study Coach's reply to one message (F09): its final text, every step it took and what it created.
 *
 * @param status COMPLETED, STEP_LIMIT if it had to stop early, or FAILED if the AI service stopped responding
 */
public record AgentResponse(String text, Status status, List<TraceStep> steps, List<CreatedItem> created) {

    public enum Status {
        COMPLETED,
        STEP_LIMIT,
        FAILED
    }

    public AgentResponse {
        steps = List.copyOf(steps);
        created = List.copyOf(created);
    }
}
