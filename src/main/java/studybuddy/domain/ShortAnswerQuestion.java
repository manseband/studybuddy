package studybuddy.domain;

/** Short-answer question, graded by the LLM against the rubric and reference answer (F07). */
public class ShortAnswerQuestion extends Question {

    private final String referenceAnswer;
    private final String rubric;

    public ShortAnswerQuestion(long id, String stem, Difficulty difficulty, int sourcePage, Long conceptId,
                               Long sourceChunkId, String referenceAnswer, String rubric) {
        super(id, stem, difficulty, sourcePage, conceptId, sourceChunkId);
        this.referenceAnswer = referenceAnswer == null ? "" : referenceAnswer.trim();
        this.rubric = rubric == null ? "" : rubric.trim();
    }

    @Override
    public boolean isValid() {
        return hasValidBase() && !referenceAnswer.isEmpty() && !rubric.isEmpty();
    }

    @Override
    public QuestionType getType() {
        return QuestionType.SHORT_ANSWER;
    }

    @Override
    public String correctAnswerText() {
        return referenceAnswer;
    }

    public String getReferenceAnswer() {
        return referenceAnswer;
    }

    public String getRubric() {
        return rubric;
    }
}
