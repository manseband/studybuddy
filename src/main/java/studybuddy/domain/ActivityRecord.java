package studybuddy.domain;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * One row of the study history (F10).
 *
 * @param activity what happened, e.g. "Quiz taken"
 * @param details  what it was about, e.g. "10 questions, medium"
 * @param result   how it went, e.g. "70% (1 pending)"; empty if the activity has no result
 */
public record ActivityRecord(LocalDateTime occurredAt, ActivityType type, String activity, String details,
                             String result) {

    private static final DateTimeFormatter DATE_LABEL = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    public String dateLabel() {
        return occurredAt.format(DATE_LABEL);
    }
}
