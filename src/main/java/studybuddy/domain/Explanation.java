package studybuddy.domain;

/**
 * Explanation of a graded question (F08).
 *
 * @param misconception   likely misconception behind a wrong answer; null when the student was correct
 * @param citation        the source excerpt used; null when no source is available
 * @param sourceAvailable false when the source material was deleted and only the answer key was used
 */
public record Explanation(String text, String misconception, Citation citation, boolean sourceAvailable) {
}
