package studybuddy.domain;

import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Parameters for generating a quiz (F06).
 * The scope is the given materials and/or concepts; if both are empty the whole course is used.
 */
public record QuizRequest(long courseId, List<Long> materialIds, List<Long> conceptIds, int questionCount,
                          Set<QuestionType> types, Difficulty difficulty) {

    public static final int MIN_QUESTIONS = 1;
    public static final int MAX_QUESTIONS = 30;

    public QuizRequest {
        materialIds = List.copyOf(materialIds);
        conceptIds = List.copyOf(conceptIds);
        types = Set.copyOf(types);
        if (questionCount < MIN_QUESTIONS || questionCount > MAX_QUESTIONS) {
            throw new IllegalArgumentException(
                    "Number of questions must be between " + MIN_QUESTIONS + " and " + MAX_QUESTIONS);
        }
        if (types.isEmpty()) {
            throw new IllegalArgumentException("Choose at least one question type");
        }
        Objects.requireNonNull(difficulty, "difficulty is required");
    }
}
