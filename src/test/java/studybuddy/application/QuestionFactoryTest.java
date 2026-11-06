package studybuddy.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import studybuddy.domain.Difficulty;
import studybuddy.domain.MultipleChoiceQuestion;
import studybuddy.domain.Question;
import studybuddy.domain.QuestionType;
import studybuddy.domain.ShortAnswerQuestion;
import studybuddy.domain.TrueFalseQuestion;

class QuestionFactoryTest {

    private final QuestionFactory factory = new QuestionFactory();

    private static Map<String, Object> base() {
        Map<String, Object> data = new HashMap<>();
        data.put("stem", "What does a Facade do?");
        data.put("difficulty", Difficulty.EASY);
        data.put("sourcePage", 3);
        data.put("conceptId", 5L);
        data.put("sourceChunkId", 9L);
        return data;
    }

    @Test
    void createsMultipleChoice() {
        Map<String, Object> data = base();
        data.put("options", List.of("A", "B", "C", "D"));
        data.put("correctIndex", 2);

        Question question = factory.createQuestion(QuestionType.MULTIPLE_CHOICE, data);

        MultipleChoiceQuestion mcq = assertInstanceOf(MultipleChoiceQuestion.class, question);
        assertEquals(2, mcq.getCorrectIndex());
        assertEquals(3, mcq.getSourcePage());
        assertEquals(5L, mcq.getConceptId());
        assertEquals(9L, mcq.getSourceChunkId());
        assertTrue(mcq.isValid());
    }

    @Test
    void createsTrueFalse() {
        Map<String, Object> data = base();
        data.put("answer", true);

        assertTrue(assertInstanceOf(TrueFalseQuestion.class, factory.createQuestion(QuestionType.TRUE_FALSE, data))
                .getCorrectAnswer());
    }

    @Test
    void createsShortAnswer() {
        Map<String, Object> data = base();
        data.put("referenceAnswer", "It hides a subsystem.");
        data.put("rubric", "Mentions hiding complexity.");

        assertEquals("It hides a subsystem.", assertInstanceOf(ShortAnswerQuestion.class,
                factory.createQuestion(QuestionType.SHORT_ANSWER, data)).getReferenceAnswer());
    }

    @Test
    void trueFalseAnswerMustBeABoolean() {
        Map<String, Object> data = base();
        data.put("answer", "true");

        assertThrows(IllegalArgumentException.class, () -> factory.createQuestion(QuestionType.TRUE_FALSE, data));
    }

    @Test
    void wrongOptionCountGivesAnInvalidQuestion() {
        Map<String, Object> data = base();
        data.put("options", List.of("A", "B"));
        data.put("correctIndex", 0);

        assertFalse(factory.createQuestion(QuestionType.MULTIPLE_CHOICE, data).isValid());
    }

    @Test
    void missingRubricGivesAnInvalidQuestion() {
        Map<String, Object> data = base();
        data.put("referenceAnswer", "An answer");

        assertFalse(factory.createQuestion(QuestionType.SHORT_ANSWER, data).isValid());
    }
}
