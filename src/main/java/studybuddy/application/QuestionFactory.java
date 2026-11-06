package studybuddy.application;

import java.util.List;
import java.util.Map;

import studybuddy.domain.Difficulty;
import studybuddy.domain.MultipleChoiceQuestion;
import studybuddy.domain.Question;
import studybuddy.domain.QuestionType;
import studybuddy.domain.ShortAnswerQuestion;
import studybuddy.domain.TrueFalseQuestion;

/**
 * Factory: creates the right {@link Question} subclass from the loosely typed data in a model reply, so
 * QuizService never needs to know which fields each question type has.
 *
 * Common keys: stem, difficulty (a Difficulty), sourcePage, conceptId, sourceChunkId.
 * Multiple choice: options, correctIndex. True/false: answer. Short answer: referenceAnswer, rubric.
 */
public class QuestionFactory {

    /**
     * @throws IllegalArgumentException if a field has the wrong type, e.g. a true/false answer that is
     *                                  not a boolean. Other problems are found by {@link Question#isValid()}.
     */
    public Question createQuestion(QuestionType type, Map<String, Object> data) {
        String stem = text(data, "stem");
        Difficulty difficulty = (Difficulty) data.get("difficulty");
        int page = number(data, "sourcePage");
        Long conceptId = (Long) data.get("conceptId");
        Long chunkId = (Long) data.get("sourceChunkId");
        return switch (type) {
            case MULTIPLE_CHOICE -> new MultipleChoiceQuestion(0, stem, difficulty, page, conceptId, chunkId,
                    options(data), number(data, "correctIndex"));
            case TRUE_FALSE -> new TrueFalseQuestion(0, stem, difficulty, page, conceptId, chunkId, bool(data));
            case SHORT_ANSWER -> new ShortAnswerQuestion(0, stem, difficulty, page, conceptId, chunkId,
                    text(data, "referenceAnswer"), text(data, "rubric"));
        };
    }

    private static String text(Map<String, Object> data, String key) {
        return data.get(key) instanceof String value ? value : "";
    }

    private static int number(Map<String, Object> data, String key) {
        if (data.get(key) instanceof Number value) {
            return value.intValue();
        }
        throw new IllegalArgumentException(key + " must be a number");
    }

    private static boolean bool(Map<String, Object> data) {
        if (data.get("answer") instanceof Boolean value) {
            return value;
        }
        throw new IllegalArgumentException("A true/false answer must be a boolean");
    }

    private static List<String> options(Map<String, Object> data) {
        if (data.get("options") instanceof List<?> values) {
            return values.stream().map(v -> v instanceof String s ? s : "").toList();
        }
        throw new IllegalArgumentException("options must be a list");
    }
}
