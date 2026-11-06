package studybuddy.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

class ValueTypesTest {

    @Test
    void difficultyStepsStayInRange() {
        assertEquals(Difficulty.MEDIUM, Difficulty.EASY.harder());
        assertEquals(Difficulty.HARD, Difficulty.HARD.harder());
        assertEquals(Difficulty.EASY, Difficulty.EASY.easier());
        assertEquals(Difficulty.HARD, Difficulty.fromString(" hard "));
        assertThrows(IllegalArgumentException.class, () -> Difficulty.fromString("extreme"));
    }

    @Test
    void questionTypeParsesCliCodes() {
        assertEquals(QuestionType.MULTIPLE_CHOICE, QuestionType.fromCode("mcq"));
        assertEquals(QuestionType.SHORT_ANSWER, QuestionType.fromCode("SHORT_ANSWER"));
        assertTrue(QuestionType.TRUE_FALSE.isObjective());
        assertFalse(QuestionType.SHORT_ANSWER.isObjective());
    }

    @Test
    void quizRequestValidatesCountTypesAndDifficulty() {
        Set<QuestionType> mcq = Set.of(QuestionType.MULTIPLE_CHOICE);
        assertThrows(IllegalArgumentException.class,
                () -> new QuizRequest(1, List.of(), List.of(), 31, mcq, Difficulty.EASY, false));
        assertThrows(IllegalArgumentException.class,
                () -> new QuizRequest(1, List.of(), List.of(), 5, Set.of(), Difficulty.EASY, false));
        assertThrows(NullPointerException.class,
                () -> new QuizRequest(1, List.of(), List.of(), 5, mcq, null, false));
        // adaptive quizzes do not need a fixed difficulty
        new QuizRequest(1, List.of(), List.of(), 5, mcq, null, true);
    }

    @Test
    void conceptMasteryWithFewEventsIsNeverWeak() {
        assertTrue(new ConceptMastery(1, "Observer", 20, 2, Trend.FLAT, false).insufficientData());
        assertThrows(IllegalArgumentException.class,
                () -> new ConceptMastery(1, "Observer", 20, 2, Trend.FLAT, true));
    }

    @Test
    void answerTracksRemovedCitations() {
        Answer answer = new Answer("text", List.of(), true, false, false)
                .withCitations(List.of(new Citation(1, 10, 2, "notes.pdf", 4, "excerpt")), true);

        assertEquals(1, answer.citations().size());
        assertTrue(answer.partiallyUnverified());
    }
}
