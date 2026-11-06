package studybuddy.grading;

import java.util.List;
import java.util.Locale;

import studybuddy.domain.GradeResult;
import studybuddy.domain.MultipleChoiceQuestion;
import studybuddy.domain.Question;
import studybuddy.domain.TrueFalseQuestion;

/** Grades multiple-choice and true/false answers by direct comparison with the answer key. */
public class RuleBasedGrader implements GradingStrategy {

    @Override
    public GradeResult grade(Question question, String answer) {
        String given = answer == null ? "" : answer.strip();
        if (given.isEmpty()) {
            return GradeResult.byRule(question.getId(), false,
                    "Not answered. The correct answer is " + question.correctAnswerText() + ".");
        }
        boolean correct = switch (question) {
            case MultipleChoiceQuestion mcq -> chosenOption(mcq, given) == mcq.getCorrectIndex();
            case TrueFalseQuestion tf -> isTrue(given) ? tf.getCorrectAnswer() : isFalse(given) && !tf.getCorrectAnswer();
            default -> throw new IllegalArgumentException("Rule-based grading cannot grade " + question.getType());
        };
        return GradeResult.byRule(question.getId(), correct,
                correct ? "Correct." : "Incorrect. The correct answer is " + question.correctAnswerText() + ".");
    }

    /** Accepts a letter ("B", "b", "B)") or the option's full text; returns -1 if neither matches. */
    private static int chosenOption(MultipleChoiceQuestion question, String given) {
        List<String> options = question.getOptions();
        String letter = given.endsWith(")") ? given.substring(0, given.length() - 1).strip() : given;
        for (int i = 0; i < options.size(); i++) {
            if (MultipleChoiceQuestion.letter(i).equalsIgnoreCase(letter) || options.get(i).equalsIgnoreCase(given)) {
                return i;
            }
        }
        return -1;
    }

    private static boolean isTrue(String given) {
        String value = given.toLowerCase(Locale.ROOT);
        return value.equals("true") || value.equals("t");
    }

    private static boolean isFalse(String given) {
        String value = given.toLowerCase(Locale.ROOT);
        return value.equals("false") || value.equals("f");
    }
}
