package studybuddy.domain;

/**
 * Grade of one answer (F07). score is in [0, 1].
 *
 * @param gradedByAI true when the LLM rubric grader produced the score
 * @param pending    true when the LLM grader failed and the answer waits to be re-graded
 * @param overridden true when the student disputed an AI grade and set it manually
 */
public record GradeResult(long questionId, double score, boolean correct, String feedback, boolean gradedByAI,
                          boolean pending, boolean overridden) {

    /** Answers scoring at least this much count as correct. */
    public static final double CORRECT_THRESHOLD = 0.5;

    public GradeResult {
        if (score < 0 || score > 1) {
            throw new IllegalArgumentException("Score must be between 0 and 1; clamp it first");
        }
        feedback = feedback == null ? "" : feedback;
    }

    /** Result of a rule-based comparison with the answer key. */
    public static GradeResult byRule(long questionId, boolean correct, String feedback) {
        return new GradeResult(questionId, correct ? 1.0 : 0.0, correct, feedback, false, false, false);
    }

    /** Result from the LLM grader; the score is clamped to [0, 1]. */
    public static GradeResult byAI(long questionId, double score, String feedback) {
        double clamped = clamp(score);
        return new GradeResult(questionId, clamped, clamped >= CORRECT_THRESHOLD, feedback, true, false, false);
    }

    /** Placeholder when the LLM grader could not grade the answer. */
    public static GradeResult pending(long questionId) {
        return new GradeResult(questionId, 0.0, false, "Pending grading", true, true, false);
    }

    /** Manual grade set by the student through Dispute. */
    public GradeResult overrideWith(double newScore) {
        if (!gradedByAI || pending) {
            throw new IllegalStateException("Only completed AI grades can be disputed");
        }
        double clamped = clamp(newScore);
        return new GradeResult(questionId, clamped, clamped >= CORRECT_THRESHOLD, feedback, true, false, true);
    }

    public static double clamp(double score) {
        if (Double.isNaN(score)) {
            return 0.0;
        }
        return Math.clamp(score, 0.0, 1.0);
    }
}
