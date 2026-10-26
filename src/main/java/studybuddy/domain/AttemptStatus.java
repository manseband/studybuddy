package studybuddy.domain;

/** Lifecycle of a quiz attempt (F07). */
public enum AttemptStatus {
    /** Answers are still being entered. */
    IN_PROGRESS,
    /** Submitted; grading has not finished yet. */
    SUBMITTED,
    /** Every answer has a grade. */
    GRADED,
    /** Objective answers are graded but at least one short answer is waiting for the LLM grader. */
    PENDING_GRADING
}
