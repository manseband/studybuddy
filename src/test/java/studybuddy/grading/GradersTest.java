package studybuddy.grading;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import studybuddy.ai.FakeLLMClient;
import studybuddy.ai.LLMException;
import studybuddy.ai.ResponseParser;
import studybuddy.domain.Difficulty;
import studybuddy.domain.GradeResult;
import studybuddy.domain.MultipleChoiceQuestion;
import studybuddy.domain.ShortAnswerQuestion;
import studybuddy.domain.TrueFalseQuestion;

class GradersTest {

    private static final MultipleChoiceQuestion MCQ = new MultipleChoiceQuestion(1, "Which hides a subsystem?",
            Difficulty.EASY, 1, null, null, List.of("Facade", "Observer", "Builder", "Command"), 0);
    private static final TrueFalseQuestion TF = new TrueFalseQuestion(2, "Adapters convert interfaces.",
            Difficulty.EASY, 1, null, null, true);
    private static final ShortAnswerQuestion SHORT = new ShortAnswerQuestion(3, "What is a Facade?",
            Difficulty.EASY, 1, null, null, "One simple interface to a subsystem.", "Mentions a simple interface.");

    private final RuleBasedGrader rules = new RuleBasedGrader();
    private final FakeLLMClient llm = new FakeLLMClient();
    private final LLMRubricGrader rubric = new LLMRubricGrader(llm, new ResponseParser());

    @BeforeAll
    static void silenceExpectedWarnings() {
        Logger.getLogger(LLMRubricGrader.class.getName()).setLevel(Level.OFF);
    }

    @ParameterizedTest
    @ValueSource(strings = {"A", "a", "A)", "Facade", "facade", " A "})
    void acceptsTheCorrectOptionByLetterOrText(String answer) {
        GradeResult result = rules.grade(MCQ, answer);

        assertTrue(result.correct());
        assertEquals(1.0, result.score());
        assertFalse(result.gradedByAI());
    }

    @ParameterizedTest
    @ValueSource(strings = {"B", "Z", "Observer", "the first one"})
    void rejectsOtherOptionsAndShowsTheAnswer(String answer) {
        GradeResult result = rules.grade(MCQ, answer);

        assertFalse(result.correct());
        assertEquals(0.0, result.score());
        assertTrue(result.feedback().contains("A) Facade"));
    }

    @Test
    void gradesTrueFalse() {
        assertTrue(rules.grade(TF, "true").correct());
        assertTrue(rules.grade(TF, "T").correct());
        assertFalse(rules.grade(TF, "false").correct());
        assertFalse(rules.grade(TF, "maybe").correct());
        TrueFalseQuestion falseQuestion = new TrueFalseQuestion(4, "Stem", Difficulty.EASY, 1, null, null, false);
        assertTrue(rules.grade(falseQuestion, "f").correct());
        assertFalse(rules.grade(falseQuestion, "maybe").correct());
    }

    @Test
    void unansweredObjectiveQuestionScoresZero() {
        GradeResult result = rules.grade(MCQ, "  ");

        assertEquals(0.0, result.score());
        assertTrue(result.feedback().startsWith("Not answered"));
    }

    @Test
    void ruleGraderRefusesShortAnswers() {
        assertThrows(IllegalArgumentException.class, () -> rules.grade(SHORT, "text"));
    }

    @Test
    void rubricGraderUsesTheModelsScoreAndFeedback() {
        llm.enqueueText("{\"score\": 0.5, \"feedback\": \"Good start.\", \"missingPoints\": [\"subsystem\"]}");

        GradeResult result = rubric.grade(SHORT, "It is simple.");

        assertEquals(0.5, result.score());
        assertTrue(result.correct());
        assertTrue(result.gradedByAI());
        assertEquals("Good start.\nMissing: subsystem", result.feedback());
        String prompt = llm.lastRequest().messages().get(0).text();
        assertTrue(prompt.contains("Mentions a simple interface."));
        assertTrue(prompt.contains("One simple interface to a subsystem."));
        assertTrue(prompt.contains("It is simple."));
    }

    @Test
    void rubricGraderClampsOutOfRangeScores() {
        llm.enqueueText("{\"score\": 7, \"feedback\": \"\"}");
        llm.enqueueText("{\"score\": -2, \"feedback\": \"\"}");

        assertEquals(1.0, rubric.grade(SHORT, "text").score());
        assertEquals(0.0, rubric.grade(SHORT, "text").score());
    }

    @Test
    void blankShortAnswerScoresZeroWithoutAModelCall() {
        GradeResult result = rubric.grade(SHORT, "   ");

        assertEquals(0.0, result.score());
        assertFalse(result.pending());
        assertEquals(0, llm.getRequests().size());
    }

    @Test
    void modelFailureMarksTheAnswerPending() {
        llm.enqueueFailure(new LLMException("rate limited", true));

        GradeResult result = rubric.grade(SHORT, "text");

        assertTrue(result.pending());
        assertEquals(0.0, result.score());
    }

    @Test
    void replyWithoutAScoreMarksTheAnswerPending() {
        llm.enqueueText("{\"feedback\": \"No score here\"}");

        assertTrue(rubric.grade(SHORT, "text").pending());
    }

    @Test
    void rubricGraderRefusesOtherTypes() {
        assertThrows(IllegalArgumentException.class, () -> rubric.grade(TF, "true"));
    }
}
