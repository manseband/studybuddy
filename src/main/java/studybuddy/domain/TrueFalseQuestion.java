package studybuddy.domain;

/** True/false question. */
public class TrueFalseQuestion extends Question {

    private final boolean correctAnswer;

    public TrueFalseQuestion(long id, String stem, Difficulty difficulty, int sourcePage, Long conceptId,
                             Long sourceChunkId, boolean correctAnswer) {
        super(id, stem, difficulty, sourcePage, conceptId, sourceChunkId);
        this.correctAnswer = correctAnswer;
    }

    @Override
    public boolean isValid() {
        return hasValidBase();
    }

    @Override
    public QuestionType getType() {
        return QuestionType.TRUE_FALSE;
    }

    @Override
    public String correctAnswerText() {
        return correctAnswer ? "True" : "False";
    }

    public boolean getCorrectAnswer() {
        return correctAnswer;
    }
}
