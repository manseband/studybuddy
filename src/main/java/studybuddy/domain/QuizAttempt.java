package studybuddy.domain;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * A student's attempt at a quiz (F07). Answers are keyed by question id; each graded answer has a
 * {@link GradeResult}. An attempt can be submitted only once.
 */
public class QuizAttempt {

    private long id;
    private final long quizId;
    private final long courseId;
    private final List<Long> questionIds;
    private final Map<Long, String> answers = new LinkedHashMap<>();
    private final Map<Long, GradeResult> results = new LinkedHashMap<>();
    private AttemptStatus status;
    private LocalDateTime submittedAt;

    public QuizAttempt(long id, long quizId, long courseId, List<Long> questionIds, AttemptStatus status,
                       LocalDateTime submittedAt) {
        this.id = id;
        this.quizId = quizId;
        this.courseId = courseId;
        this.questionIds = List.copyOf(questionIds);
        this.status = status;
        this.submittedAt = submittedAt;
    }

    /** A new, empty attempt for the quiz. */
    public static QuizAttempt start(Quiz quiz) {
        List<Long> ids = quiz.getQuestions().stream().map(Question::getId).toList();
        return new QuizAttempt(0, quiz.getId(), quiz.getCourseId(), ids, AttemptStatus.IN_PROGRESS, null);
    }

    /** Records the answer to a question of this quiz. */
    public void answer(long questionId, String answer) {
        requireInProgress();
        if (!questionIds.contains(questionId)) {
            throw new IllegalArgumentException("Question " + questionId + " is not part of quiz " + quizId);
        }
        answers.put(questionId, answer == null ? "" : answer.trim());
    }

    /** The answer given, or "" for an unanswered question. */
    public String answerFor(long questionId) {
        return answers.getOrDefault(questionId, "");
    }

    /** Locks the answers. Rejects a second submission. */
    public void submit() {
        requireInProgress();
        status = AttemptStatus.SUBMITTED;
        submittedAt = LocalDateTime.now();
    }

    public void recordResult(GradeResult result) {
        if (status == AttemptStatus.IN_PROGRESS) {
            throw new IllegalStateException("Submit the attempt before grading it");
        }
        if (!questionIds.contains(result.questionId())) {
            throw new IllegalArgumentException("Question " + result.questionId() + " is not part of quiz " + quizId);
        }
        results.put(result.questionId(), result);
        updateStatus();
    }

    /** Sum of the scores (each 0-1); pending answers count as 0 until they are graded. */
    public double getTotalScore() {
        return results.values().stream().filter(r -> !r.pending()).mapToDouble(GradeResult::score).sum();
    }

    /** Highest possible total: one point per question. */
    public int getMaxScore() {
        return questionIds.size();
    }

    /** Total score as a percentage 0-100. */
    public double getPercentage() {
        return questionIds.isEmpty() ? 0.0 : 100.0 * getTotalScore() / questionIds.size();
    }

    public Optional<GradeResult> resultFor(long questionId) {
        return Optional.ofNullable(results.get(questionId));
    }

    private void updateStatus() {
        if (!results.keySet().containsAll(questionIds)) {
            status = AttemptStatus.SUBMITTED;
        } else if (results.values().stream().anyMatch(GradeResult::pending)) {
            status = AttemptStatus.PENDING_GRADING;
        } else {
            status = AttemptStatus.GRADED;
        }
    }

    private void requireInProgress() {
        if (status != AttemptStatus.IN_PROGRESS) {
            throw new IllegalStateException("This quiz attempt has already been submitted");
        }
    }

    public long getId() {
        return id;
    }

    public void setId(long id) {
        this.id = id;
    }

    public long getQuizId() {
        return quizId;
    }

    public long getCourseId() {
        return courseId;
    }

    public List<Long> getQuestionIds() {
        return questionIds;
    }

    public Map<Long, String> getAnswers() {
        return Map.copyOf(answers);
    }

    public Map<Long, GradeResult> getResults() {
        return Map.copyOf(results);
    }

    public AttemptStatus getStatus() {
        return status;
    }

    public LocalDateTime getSubmittedAt() {
        return submittedAt;
    }

    /** Used by the repository when loading a stored attempt. */
    public void restore(Map<Long, String> storedAnswers, Map<Long, GradeResult> storedResults) {
        answers.clear();
        answers.putAll(storedAnswers);
        results.clear();
        results.putAll(storedResults);
    }
}
