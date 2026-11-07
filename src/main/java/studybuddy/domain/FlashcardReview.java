package studybuddy.domain;

import java.time.LocalDateTime;

/**
 * One recall rating of a flashcard: mastery evidence for its concept (F08). conceptId is null if untagged.
 * cardId is null once the card's deck has been deleted; the rating is kept as a record of what was studied.
 */
public record FlashcardReview(Long cardId, long courseId, Long conceptId, Rating rating, LocalDateTime reviewedAt) {
}
