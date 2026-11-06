package studybuddy.domain;

import java.time.LocalDateTime;

/** One recall rating of a flashcard: mastery evidence for its concept (F09). conceptId is null if untagged. */
public record FlashcardReview(long cardId, long courseId, Long conceptId, Rating rating, LocalDateTime reviewedAt) {
}
