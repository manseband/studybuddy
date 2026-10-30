package studybuddy.domain;

import java.time.LocalDateTime;

/** One question and its answer in a course's Q&A history (F04). */
public record QaEntry(long id, long courseId, String question, Answer answer, LocalDateTime askedAt) {

    public QaEntry withId(long newId) {
        return new QaEntry(newId, courseId, question, answer, askedAt);
    }
}
