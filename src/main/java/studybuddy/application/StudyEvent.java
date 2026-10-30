package studybuddy.application;

import java.time.LocalDateTime;

/**
 * Something that changed in the student's data, published on the {@link EventBus} so that the
 * GUI panels, dashboard and history can refresh without the services knowing about them.
 *
 * @param courseId the affected course, or 0 when the event is not tied to one course
 * @param message  short human-readable description, also used for the study history (F13)
 */
public record StudyEvent(Type type, long courseId, String message, LocalDateTime occurredAt) {

    public enum Type {
        COURSE_CHANGED,
        MATERIAL_ADDED,
        MATERIAL_REMOVED,
        SUMMARY_CREATED,
        CONCEPTS_CHANGED,
        QUESTION_ANSWERED,
        QUIZ_CREATED,
        QUIZ_SUBMITTED,
        FLASHCARDS_CHANGED,
        FLASHCARD_REVIEWED,
        PLAN_CHANGED,
        COACH_RESPONDED
    }

    public static StudyEvent of(Type type, long courseId, String message) {
        return new StudyEvent(type, courseId, message, LocalDateTime.now());
    }
}
