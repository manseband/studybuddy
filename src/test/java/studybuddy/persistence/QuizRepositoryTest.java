package studybuddy.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDateTime;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import studybuddy.domain.AttemptStatus;
import studybuddy.domain.Concept;
import studybuddy.domain.Course;
import studybuddy.domain.Difficulty;
import studybuddy.domain.GradeResult;
import studybuddy.domain.MultipleChoiceQuestion;
import studybuddy.domain.Quiz;
import studybuddy.domain.QuizAttempt;
import studybuddy.domain.ShortAnswerQuestion;
import studybuddy.domain.TrueFalseQuestion;

class QuizRepositoryTest {

    private Database db;
    private QuizRepository quizzes;
    private AttemptRepository attempts;
    private long courseId;
    private long conceptId;

    @BeforeEach
    void setUp() {
        db = Database.inMemory();
        quizzes = new QuizRepository(db);
        attempts = new AttemptRepository(db);
        courseId = new CourseRepository(db).save(new Course(0, "EECS3311", "")).getId();
        conceptId = new ConceptRepository(db).save(new Concept(0, courseId, "Facade", "Hides a subsystem.", 3)).getId();
    }

    @AfterEach
    void tearDown() {
        db.close();
    }

    private Quiz savedQuiz(String stemPrefix) {
        Quiz quiz = new Quiz(0, courseId, LocalDateTime.of(2026, 11, 6, 12, 0), Difficulty.MEDIUM);
        quiz.addQuestion(new MultipleChoiceQuestion(0, stemPrefix + " mcq", Difficulty.MEDIUM, 2, conceptId, null,
                List.of("A", "B", "C", "D"), 3));
        quiz.addQuestion(new TrueFalseQuestion(0, stemPrefix + " tf", Difficulty.MEDIUM, 3, null, null, false));
        quiz.addQuestion(new ShortAnswerQuestion(0, stemPrefix + " short", Difficulty.MEDIUM, 4, null, null,
                "Reference", "Rubric"));
        return quizzes.save(quiz);
    }

    @Test
    void savesAndLoadsEveryQuestionType() {
        Quiz saved = savedQuiz("Q");
        assertTrue(saved.getId() > 0);
        assertTrue(saved.getQuestions().stream().allMatch(q -> q.getId() > 0));

        Quiz loaded = quizzes.findById(saved.getId()).orElseThrow();

        assertEquals(Difficulty.MEDIUM, loaded.getDifficulty());
        assertEquals(saved.getCreatedAt(), loaded.getCreatedAt());
        assertEquals(3, loaded.size());
        MultipleChoiceQuestion mcq = assertInstanceOf(MultipleChoiceQuestion.class, loaded.getQuestions().get(0));
        assertEquals(List.of("A", "B", "C", "D"), mcq.getOptions());
        assertEquals(3, mcq.getCorrectIndex());
        assertEquals(conceptId, mcq.getConceptId());
        assertEquals(2, mcq.getSourcePage());
        TrueFalseQuestion tf = assertInstanceOf(TrueFalseQuestion.class, loaded.getQuestions().get(1));
        assertFalse(tf.getCorrectAnswer());
        assertNull(tf.getConceptId());
        ShortAnswerQuestion shortAnswer = assertInstanceOf(ShortAnswerQuestion.class, loaded.getQuestions().get(2));
        assertEquals("Reference", shortAnswer.getReferenceAnswer());
        assertEquals("Rubric", shortAnswer.getRubric());
    }

    @Test
    void savedQuizCannotBeSavedAgain() {
        Quiz saved = savedQuiz("Q");

        assertThrows(IllegalArgumentException.class, () -> quizzes.save(saved));
    }

    @Test
    void listsNewestFirstAndFindsRecentStems() {
        savedQuiz("Old");
        Quiz newer = savedQuiz("New");

        assertEquals(newer.getId(), quizzes.findByCourse(courseId).get(0).getId());
        assertEquals(List.of("New mcq", "New tf", "New short"), quizzes.findRecentStems(courseId, 1));
        assertEquals(6, quizzes.findRecentStems(courseId, 5).size());
    }

    @Test
    void savesAndLoadsAGradedAttempt() {
        Quiz quiz = savedQuiz("Q");
        QuizAttempt attempt = QuizAttempt.start(quiz);
        long mcqId = quiz.getQuestions().get(0).getId();
        long tfId = quiz.getQuestions().get(1).getId();
        long shortId = quiz.getQuestions().get(2).getId();
        attempt.answer(mcqId, "D");
        attempt.answer(shortId, "My answer");
        attempt.submit();
        attempt.recordResult(GradeResult.byRule(mcqId, true, "Correct."));
        attempt.recordResult(GradeResult.byRule(tfId, false, "Not answered."));
        attempt.recordResult(GradeResult.pending(shortId));

        attempts.save(attempt);
        QuizAttempt loaded = attempts.findById(attempt.getId()).orElseThrow();

        assertEquals(AttemptStatus.PENDING_GRADING, loaded.getStatus());
        assertEquals(List.of(mcqId, tfId, shortId), loaded.getQuestionIds());
        assertEquals("D", loaded.answerFor(mcqId));
        assertEquals("", loaded.answerFor(tfId));
        assertEquals(attempt.getSubmittedAt(), loaded.getSubmittedAt());
        assertEquals(1.0, loaded.getTotalScore());
        assertTrue(loaded.resultFor(shortId).orElseThrow().pending());
    }

    @Test
    void updatingAnAttemptKeepsOneRowPerQuestion() {
        Quiz quiz = savedQuiz("Q");
        QuizAttempt attempt = QuizAttempt.start(quiz);
        long shortId = quiz.getQuestions().get(2).getId();
        attempt.answer(shortId, "My answer");
        attempt.submit();
        quiz.getQuestions().forEach(q -> attempt.recordResult(GradeResult.pending(q.getId())));
        attempts.save(attempt);

        quiz.getQuestions().forEach(q -> attempt.recordResult(GradeResult.byAI(q.getId(), 0.75, "Fine")));
        attempts.save(attempt);

        QuizAttempt loaded = attempts.findById(attempt.getId()).orElseThrow();
        assertEquals(AttemptStatus.GRADED, loaded.getStatus());
        assertEquals(3, loaded.getQuestionIds().size());
        assertEquals(2.25, loaded.getTotalScore());
        assertEquals(1, attempts.findByCourse(courseId).size());
    }

    @Test
    void attemptWithoutGradesLoadsWithoutResults() {
        Quiz quiz = savedQuiz("Q");
        QuizAttempt attempt = QuizAttempt.start(quiz);
        attempt.submit();

        attempts.save(attempt);

        assertTrue(attempts.findById(attempt.getId()).orElseThrow().getResults().isEmpty());
    }
}
