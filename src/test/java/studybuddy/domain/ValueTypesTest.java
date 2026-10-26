package studybuddy.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
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
    void planRequestCountsHoursAndDaysOff() {
        LocalDate monday = LocalDate.of(2026, 11, 2);
        PlanRequest request = new PlanRequest(1, monday.plusDays(30),
                Map.of(DayOfWeek.MONDAY, 2.0, DayOfWeek.SATURDAY, 3.0), Set.of(monday.plusWeeks(1)), List.of());

        assertEquals(5.0, request.weeklyHours());
        assertEquals(2.0, request.hoursOn(monday));
        assertEquals(0.0, request.hoursOn(monday.plusWeeks(1)));
        assertEquals(0.0, request.hoursOn(monday.plusDays(1)));
        assertThrows(IllegalArgumentException.class,
                () -> new PlanRequest(1, monday, Map.of(DayOfWeek.MONDAY, -1.0), Set.of(), List.of()));
    }

    @Test
    void topicMasteryWithFewEventsIsNeverWeak() {
        assertTrue(new TopicMastery(1, "Observer", 20, 2, Trend.FLAT, false).insufficientData());
        assertThrows(IllegalArgumentException.class,
                () -> new TopicMastery(1, "Observer", 20, 2, Trend.FLAT, true));
    }

    @Test
    void answerTracksRemovedCitations() {
        Answer answer = new Answer("text", List.of(), true, false)
                .withCitations(List.of(new Citation(1, 10, 2, "notes.pdf", 4, "excerpt")), true);

        assertEquals(1, answer.citations().size());
        assertTrue(answer.partiallyUnverified());
    }

    @Test
    void summaryMarkdownMarksUnverifiedPoints() {
        Summary summary = new Summary(0, 3, SummaryLength.BRIEF, 1, 12, "Overview text",
                List.of(new KeyPoint("Facade hides subsystems", List.of(2, 3), true),
                        new KeyPoint("Unsupported claim", List.of(), false)),
                List.of("Facade"), LocalDateTime.of(2026, 10, 26, 12, 0));

        String md = summary.toMarkdown("Patterns.pdf");

        assertTrue(md.contains("# Summary: Patterns.pdf"));
        assertTrue(md.contains("- Facade hides subsystems (p. 2, 3)"));
        assertTrue(md.contains("Unsupported claim _[source unverified]_"));
        assertTrue(md.contains("## Key terms"));
    }
}
