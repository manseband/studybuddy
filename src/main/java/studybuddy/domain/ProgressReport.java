package studybuddy.domain;

import java.util.List;

/**
 * A course's progress (F10). Calculated on demand from the stored records.
 *
 * @param gradedQuizzes how many fully graded attempts the average covers
 * @param averageScore  their average percentage; 0 if there are none
 * @param currentStreak consecutive study days up to today, or up to yesterday if today has no study yet
 * @param history       all activity, newest first
 */
public record ProgressReport(String courseName, int gradedQuizzes, double averageScore, int currentStreak,
                             List<ConceptMastery> mastery, List<ActivityRecord> history) {

    public String averageScoreText() {
        if (gradedQuizzes == 0) {
            return "No graded quizzes yet";
        }
        return String.format("%.0f%% over %d %s", averageScore, gradedQuizzes, gradedQuizzes == 1 ? "quiz" : "quizzes");
    }

    public String streakText() {
        return currentStreak + (currentStreak == 1 ? " day" : " days");
    }
}
