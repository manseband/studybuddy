package studybuddy.domain;

import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Multiple-choice question: exactly 4 distinct options, one of them correct. */
public class MultipleChoiceQuestion extends Question {

    public static final int OPTION_COUNT = 4;

    private final List<String> options;
    private final int correctIndex;

    public MultipleChoiceQuestion(long id, String stem, Difficulty difficulty, int sourcePage, Long conceptId,
                                  Long sourceChunkId, List<String> options, int correctIndex) {
        super(id, stem, difficulty, sourcePage, conceptId, sourceChunkId);
        this.options = options == null ? List.of() : options.stream().map(o -> o == null ? "" : o.trim()).toList();
        this.correctIndex = correctIndex;
    }

    @Override
    public boolean isValid() {
        if (!hasValidBase() || options.size() != OPTION_COUNT) {
            return false;
        }
        if (correctIndex < 0 || correctIndex >= OPTION_COUNT) {
            return false;
        }
        Set<String> distinct = new HashSet<>();
        for (String option : options) {
            if (option.isEmpty() || !distinct.add(option.toLowerCase(Locale.ROOT))) {
                return false;
            }
        }
        return true;
    }

    @Override
    public QuestionType getType() {
        return QuestionType.MULTIPLE_CHOICE;
    }

    @Override
    public String correctAnswerText() {
        return letter(correctIndex) + ") " + options.get(correctIndex);
    }

    /** Index 0 -> "A". */
    public static String letter(int index) {
        return String.valueOf((char) ('A' + index));
    }

    public List<String> getOptions() {
        return options;
    }

    public int getCorrectIndex() {
        return correctIndex;
    }
}
