package studybuddy.grading;

import studybuddy.domain.GradeResult;
import studybuddy.domain.Question;

/** Strategy for grading one answer (F07): QuizService picks a grader by question type. */
public interface GradingStrategy {

    /** @param answer the student's answer; "" if the question was left unanswered */
    GradeResult grade(Question question, String answer);
}
