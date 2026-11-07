package studybuddy.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import studybuddy.ai.FakeLLMClient;
import studybuddy.ai.ResponseParser;
import studybuddy.domain.ActivityRecord;
import studybuddy.domain.ActivityType;
import studybuddy.domain.AttemptStatus;
import studybuddy.domain.Concept;
import studybuddy.domain.Course;
import studybuddy.domain.Difficulty;
import studybuddy.domain.Flashcard;
import studybuddy.domain.GradeResult;
import studybuddy.domain.Material;
import studybuddy.domain.MaterialStatus;
import studybuddy.domain.ProgressReport;
import studybuddy.domain.Quiz;
import studybuddy.domain.QuizAttempt;
import studybuddy.domain.Rating;
import studybuddy.domain.ReportFormat;
import studybuddy.domain.TrueFalseQuestion;
import studybuddy.persistence.AttemptRepository;
import studybuddy.persistence.CoachConversationRepository;
import studybuddy.persistence.ConceptRepository;
import studybuddy.persistence.CourseRepository;
import studybuddy.persistence.Database;
import studybuddy.persistence.FlashcardRepository;
import studybuddy.persistence.MaterialRepository;
import studybuddy.persistence.QaHistoryRepository;
import studybuddy.persistence.QuizRepository;

/** F10 with real repositories. Records are stored directly, so that their dates can be chosen. */
class ProgressServiceTest {

    private static final LocalDateTime NOV_1 = LocalDateTime.of(2026, 11, 1, 9, 0);
    private static final LocalDateTime NOV_2 = NOV_1.plusDays(1);
    private static final LocalDateTime NOV_3 = NOV_1.plusDays(2);

    @TempDir
    Path dir;

    private Database db;
    private EventBus bus;
    private QuizRepository quizzes;
    private AttemptRepository attempts;
    private FlashcardRepository cards;
    private ConceptRepository concepts;
    private ProgressService service;
    private long courseId;

    @BeforeEach
    void setUp() {
        db = Database.inMemory();
        bus = new EventBus();
        quizzes = new QuizRepository(db);
        attempts = new AttemptRepository(db);
        cards = new FlashcardRepository(db);
        concepts = new ConceptRepository(db);
        CourseRepository courses = new CourseRepository(db);
        MasteryService mastery = new MasteryService(attempts, quizzes, concepts, cards, new FakeLLMClient(),
                new ResponseParser(), 60);
        service = new ProgressService(courses, new MaterialRepository(db), new QaHistoryRepository(db), quizzes, attempts,
                cards, new CoachConversationRepository(db), mastery, bus);
        courseId = courses.save(new Course(0, "EECS3311", "")).getId();
    }

    @AfterEach
    void tearDown() {
        db.close();
    }

    private Quiz storeQuiz(int questions, LocalDateTime createdAt) {
        Quiz quiz = new Quiz(0, courseId, createdAt, Difficulty.MEDIUM);
        for (int i = 0; i < questions; i++) {
            quiz.addQuestion(new TrueFalseQuestion(0, "Statement " + i, Difficulty.MEDIUM, 1, null, null, true));
        }
        return quizzes.save(quiz);
    }

    /** A submitted attempt with one grade per question, given as a score; a negative score leaves it pending. */
    private void storeAttempt(Quiz quiz, LocalDateTime submittedAt, double... scores) {
        List<Long> ids = quiz.getQuestions().stream().map(q -> q.getId()).toList();
        QuizAttempt attempt = new QuizAttempt(0, quiz.getId(), courseId, ids, AttemptStatus.SUBMITTED, submittedAt);
        for (int i = 0; i < scores.length; i++) {
            attempt.recordResult(scores[i] < 0 ? GradeResult.pending(ids.get(i))
                    : GradeResult.byAI(ids.get(i), scores[i], ""));
        }
        attempts.save(attempt);
    }

    private void storeReview(Rating rating, LocalDateTime at) {
        Flashcard card = cards.saveDeck(List.of(Flashcard.create(courseId, 0, null, "Front", "Back",
                at.toLocalDate()))).get(0);
        cards.recordReview(card, rating, at);
    }

    private List<ActivityRecord> history() {
        return service.getHistory(courseId, null, null, null);
    }

    @Test
    void anEmptyCourseHasAnEmptyReport() {
        ProgressReport report = service.getProgress(courseId);

        assertEquals("EECS3311", report.courseName());
        assertEquals(0, report.gradedQuizzes());
        assertEquals(0, report.currentStreak());
        assertTrue(report.history().isEmpty());
        assertEquals("No graded quizzes yet", report.averageScoreText());
    }

    @Test
    void theHistoryListsEveryKindOfActivityNewestFirst() {
        new MaterialRepository(db).save(new Material(0, courseId, "patterns.pdf", "pdf", MaterialStatus.INDEXED, "hash", 12,
                500, NOV_1));
        Quiz quiz = storeQuiz(1, NOV_2);
        storeAttempt(quiz, NOV_3, 1);
        cards.saveDeck(List.of(Flashcard.create(courseId, 0, null, "A", "B", NOV_1.toLocalDate()),
                Flashcard.create(courseId, 0, null, "C", "D", NOV_1.toLocalDate())));
        bus.publish(new StudyEvent(StudyEvent.Type.COACH_CONVERSATION_STARTED, courseId, "Quiz me", NOV_1.minusDays(1)));

        List<ActivityRecord> history = history();

        assertEquals(List.of("Deck created", "Quiz taken", "Quiz created", "Material uploaded", "Coach conversation"),
                history.stream().map(ActivityRecord::activity).toList());
        assertEquals("2 cards", history.get(0).details());
        assertEquals("1 question, medium", history.get(1).details());
        assertEquals("100%", history.get(1).result());
        assertEquals("patterns.pdf, 12 pages", history.get(3).details());
        assertEquals("Quiz me", history.get(4).details());
    }

    @Test
    void aQuizResultMentionsPendingAndDisputedGrades() {
        Quiz quiz = storeQuiz(2, NOV_1);
        List<Long> ids = quiz.getQuestions().stream().map(q -> q.getId()).toList();
        QuizAttempt attempt = new QuizAttempt(0, quiz.getId(), courseId, ids, AttemptStatus.SUBMITTED, NOV_2);
        attempt.recordResult(GradeResult.byAI(ids.get(0), 0, "").overrideWith(1));
        attempt.recordResult(GradeResult.pending(ids.get(1)));
        attempts.save(attempt);

        assertEquals("50% (1 pending, 1 disputed)", history().get(0).result());
    }

    @Test
    void reviewsOfTheSameDayShareOneRow() {
        storeReview(Rating.GOOD, NOV_1);
        storeReview(Rating.AGAIN, NOV_1.plusHours(1));
        storeReview(Rating.GOOD, NOV_1.plusHours(2));
        storeReview(Rating.EASY, NOV_2);

        List<ActivityRecord> reviews = service.getHistory(courseId, null, null, ActivityType.FLASHCARDS).stream()
                .filter(r -> r.activity().equals("Cards reviewed")).toList();

        assertEquals(2, reviews.size());
        assertEquals("1 review", reviews.get(0).details());
        assertEquals("1 easy", reviews.get(0).result());
        assertEquals("3 reviews", reviews.get(1).details());
        assertEquals("1 again, 2 good", reviews.get(1).result());
        assertEquals(NOV_1.plusHours(2), reviews.get(1).occurredAt());
    }

    @Test
    void theFilterKeepsTheRangeIncludingBothEndsAndTheType() {
        storeQuiz(1, NOV_1);
        storeQuiz(1, NOV_2);
        storeQuiz(1, NOV_3);
        storeReview(Rating.GOOD, NOV_2);

        LocalDate nov2 = NOV_2.toLocalDate();
        assertEquals(2, service.getHistory(courseId, nov2, null, ActivityType.QUIZ).size());
        assertEquals(2, service.getHistory(courseId, null, nov2, ActivityType.QUIZ).size());
        assertEquals(1, service.getHistory(courseId, nov2, nov2, ActivityType.QUIZ).size());
        assertEquals(0, service.getHistory(courseId, nov2, nov2, ActivityType.COACH).size());
    }

    @Test
    void aStartDateAfterTheEndDateIsRejected() {
        LocalDate later = NOV_2.toLocalDate();
        LocalDate earlier = NOV_1.toLocalDate();

        assertThrows(IllegalArgumentException.class, () -> service.getHistory(courseId, later, earlier, null));
    }

    @Test
    void theAverageCoversFullyGradedQuizzesOnly() {
        Quiz quiz = storeQuiz(2, NOV_1);
        storeAttempt(quiz, NOV_1, 1, 1);
        storeAttempt(quiz, NOV_2, 1, 0);
        storeAttempt(quiz, NOV_3, 1, -1);

        ProgressReport report = service.getProgress(courseId);

        assertEquals(2, report.gradedQuizzes());
        assertEquals(75.0, report.averageScore());
        assertEquals("75% over 2 quizzes", report.averageScoreText());
    }

    @Test
    void theStreakCountsDaysUpToTodayOrYesterday() {
        LocalDate today = LocalDate.of(2026, 11, 6);

        assertEquals(0, ProgressService.currentStreak(Set.of(), today));
        assertEquals(1, ProgressService.currentStreak(Set.of(today), today));
        assertEquals(2, ProgressService.currentStreak(Set.of(today.minusDays(1), today.minusDays(2)), today));
        assertEquals(3, ProgressService.currentStreak(Set.of(today, today.minusDays(1), today.minusDays(2)), today));
        assertEquals(1, ProgressService.currentStreak(Set.of(today, today.minusDays(2)), today));
        assertEquals(0, ProgressService.currentStreak(Set.of(today.minusDays(2)), today));
    }

    @Test
    void onlyStudyingKeepsTheStreak() {
        LocalDateTime now = LocalDateTime.now();
        storeQuiz(1, now);
        cards.saveDeck(List.of(Flashcard.create(courseId, 0, null, "A", "B", now.toLocalDate())));
        assertEquals(0, service.getProgress(courseId).currentStreak());

        storeReview(Rating.GOOD, now);
        storeAttempt(storeQuiz(1, now), now.minusDays(1), 1);
        bus.publish(new StudyEvent(StudyEvent.Type.COACH_CONVERSATION_STARTED, courseId, "Hi", now.minusDays(2)));

        assertEquals(3, service.getProgress(courseId).currentStreak());
    }

    @Test
    void deletingADeckKeepsMasteryStreakAndReviewHistory() {
        long conceptId = concepts.save(new Concept(0, courseId, "Facade", "", 3)).getId();
        LocalDateTime now = LocalDateTime.now();
        Flashcard card = cards.saveDeck(List.of(Flashcard.create(courseId, 0, conceptId, "Front", "Back",
                now.toLocalDate()))).get(0);
        cards.recordReview(card, Rating.GOOD, now);
        ProgressReport before = service.getProgress(courseId);

        cards.deleteDeck(card.getDeckId());

        ProgressReport after = service.getProgress(courseId);
        assertEquals(1, before.currentStreak());
        assertEquals(before.currentStreak(), after.currentStreak());
        assertEquals(before.mastery(), after.mastery());
        assertEquals(List.of("Cards reviewed"), after.history().stream().map(ActivityRecord::activity).toList());
    }

    @Test
    void aCoachConversationKeepsOneShortLine() {
        bus.publish(StudyEvent.of(StudyEvent.Type.COACH_CONVERSATION_STARTED, courseId, "word\nword " + "x".repeat(300)));
        bus.publish(StudyEvent.of(StudyEvent.Type.COACH_RESPONDED, courseId, "ignored"));

        List<ActivityRecord> coach = service.getHistory(courseId, null, null, ActivityType.COACH);

        assertEquals(1, coach.size());
        assertEquals(ProgressService.MAX_COACH_LINE_LENGTH, coach.get(0).details().length());
        assertTrue(coach.get(0).details().startsWith("word word x"), coach.get(0).details());
        assertTrue(coach.get(0).details().endsWith("..."), coach.get(0).details());
    }

    @Test
    void exportsMarkdown() throws IOException {
        concepts.save(new Concept(0, courseId, "Facade", "", 3));
        storeAttempt(storeQuiz(2, NOV_1), NOV_2, 1, 0);

        Path file = service.exportReport(courseId, ReportFormat.MARKDOWN, dir.resolve("report.md"));

        String text = Files.readString(file);
        assertTrue(text.startsWith("# Progress report: EECS3311"), text);
        assertTrue(text.contains("- Average score: 50% over 1 quiz"), text);
        assertTrue(text.contains("| Facade | - | 0 | flat | Not enough data |"), text);
        assertTrue(text.contains("| 2026-11-02 09:00 | Quiz taken | 2 questions, medium | 50% |"), text);
    }

    @Test
    void exportsCsvWithCellsQuotedWhereNeeded() throws IOException {
        storeAttempt(storeQuiz(2, NOV_1), NOV_2, 1, 0);

        Path file = service.exportReport(courseId, ReportFormat.CSV, dir.resolve("report.csv"));

        List<String> lines = Files.readAllLines(file);
        assertEquals("Course,EECS3311", lines.get(0));
        assertTrue(lines.contains("Concept,Mastery,Evidence,Trend,Status"), lines.toString());
        assertTrue(lines.contains("Date,Activity,Details,Result"), lines.toString());
        assertTrue(lines.contains("2026-11-02 09:00,Quiz taken,\"2 questions, medium\",50%"), lines.toString());
    }

    @Test
    void aLocationThatCannotBeWrittenIsReported() {
        Path noSuchFolder = dir.resolve("missing").resolve("report.md");

        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> service.exportReport(courseId, ReportFormat.MARKDOWN, noSuchFolder));

        assertTrue(e.getMessage().contains("Choose another location"), e.getMessage());
    }
}
