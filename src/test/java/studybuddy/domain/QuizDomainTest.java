package studybuddy.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDateTime;
import java.util.List;

import org.junit.jupiter.api.Test;

class QuizDomainTest {

    private static MultipleChoiceQuestion mcq(List<String> options, int correct) {
        return new MultipleChoiceQuestion(1, "Which pattern hides a subsystem?", Difficulty.EASY, 2, 5L, null,
                options, correct);
    }

    @Test
    void multipleChoiceNeedsFourDistinctOptionsAndOneCorrect() {
        assertTrue(mcq(List.of("Facade", "Adapter", "Observer", "Strategy"), 0).isValid());
        assertFalse(mcq(List.of("Facade", "Adapter", "Observer"), 0).isValid());
        assertFalse(mcq(List.of("Facade", "facade", "Observer", "Strategy"), 0).isValid());
        assertFalse(mcq(List.of("Facade", "Adapter", "Observer", "Strategy"), 4).isValid());
        assertFalse(mcq(List.of("Facade", " ", "Observer", "Strategy"), 0).isValid());
        assertEquals("A) Facade", mcq(List.of("Facade", "Adapter", "Observer", "Strategy"), 0).correctAnswerText());
    }

    @Test
    void shortAnswerNeedsReferenceAndRubric() {
        assertTrue(new ShortAnswerQuestion(1, "Define Observer.", Difficulty.MEDIUM, 3, null, null,
                "Subjects notify subscribed observers", "Mentions subject and notification").isValid());
        assertFalse(new ShortAnswerQuestion(1, "Define Observer.", Difficulty.MEDIUM, 3, null, null,
                "Subjects notify subscribed observers", "").isValid());
        assertFalse(new TrueFalseQuestion(1, "", Difficulty.EASY, 1, null, null, true).isValid());
    }

    @Test
    void aiScoresAreClampedAndOverridable() {
        GradeResult result = GradeResult.byAI(1, 1.7, "Good");
        assertEquals(1.0, result.score());
        assertTrue(result.correct());

        GradeResult disputed = GradeResult.byAI(1, 0.2, "Missing points").overrideWith(0.9);
        assertTrue(disputed.overridden());
        assertTrue(disputed.correct());
        assertThrows(IllegalStateException.class, () -> GradeResult.byRule(1, true, "").overrideWith(0.5));
        assertEquals(0.0, GradeResult.clamp(Double.NaN));
    }

    @Test
    void attemptIsGradedOnceAndTracksPendingAnswers() {
        Quiz quiz = new Quiz(7, 1, LocalDateTime.of(2026, 10, 26, 10, 0), Difficulty.EASY);
        Question q1 = mcq(List.of("Facade", "Adapter", "Observer", "Strategy"), 0);
        q1.setId(1);
        Question q2 = new ShortAnswerQuestion(2, "Define Observer.", Difficulty.MEDIUM, 3, null, null, "ref", "rubric");
        quiz.addQuestion(q1);
        quiz.addQuestion(q2);

        QuizAttempt attempt = QuizAttempt.start(quiz);
        attempt.answer(1, "A");
        assertThrows(IllegalArgumentException.class, () -> attempt.answer(99, "x"));
        attempt.submit();
        assertThrows(IllegalStateException.class, attempt::submit);
        assertThrows(IllegalStateException.class, () -> attempt.answer(2, "late"));
        assertEquals("", attempt.answerFor(2));

        attempt.recordResult(GradeResult.byRule(1, true, ""));
        assertEquals(AttemptStatus.SUBMITTED, attempt.getStatus());
        attempt.recordResult(GradeResult.pending(2));
        assertEquals(AttemptStatus.PENDING_GRADING, attempt.getStatus());
        assertEquals(1.0, attempt.getTotalScore());

        attempt.recordResult(GradeResult.byAI(2, 0.5, "Partly right"));
        assertEquals(AttemptStatus.GRADED, attempt.getStatus());
        assertEquals(75.0, attempt.getPercentage());
    }
}
