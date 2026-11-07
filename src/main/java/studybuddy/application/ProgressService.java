package studybuddy.application;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.stream.Collectors;

import studybuddy.domain.ActivityRecord;
import studybuddy.domain.ActivityType;
import studybuddy.domain.AttemptStatus;
import studybuddy.domain.Course;
import studybuddy.domain.Flashcard;
import studybuddy.domain.FlashcardReview;
import studybuddy.domain.GradeResult;
import studybuddy.domain.Material;
import studybuddy.domain.ProgressReport;
import studybuddy.domain.QaEntry;
import studybuddy.domain.Quiz;
import studybuddy.domain.QuizAttempt;
import studybuddy.domain.Rating;
import studybuddy.domain.ReportFormat;
import studybuddy.persistence.AttemptRepository;
import studybuddy.persistence.CoachConversationRepository;
import studybuddy.persistence.CourseRepository;
import studybuddy.persistence.FlashcardRepository;
import studybuddy.persistence.MaterialRepository;
import studybuddy.persistence.QaHistoryRepository;
import studybuddy.persistence.QuizRepository;

/**
 * F13: a course's progress and study history, calculated from the stored records without any AI.
 * Quizzes, flashcards, questions and materials already store their dates, so the history reads those tables.
 * Study Coach conversations are stored nowhere else: as an Observer, this service keeps one line for each.
 */
public class ProgressService implements StudyEventListener {

    /** Length kept of the message that started a coach conversation. */
    static final int MAX_COACH_LINE_LENGTH = 100;

    private final CourseRepository courses;
    private final MaterialRepository materials;
    private final QaHistoryRepository questions;
    private final QuizRepository quizzes;
    private final AttemptRepository attempts;
    private final FlashcardRepository flashcards;
    private final CoachConversationRepository coachConversations;
    private final MasteryService masteryService;

    public ProgressService(CourseRepository courses, MaterialRepository materials, QaHistoryRepository questions,
                           QuizRepository quizzes, AttemptRepository attempts, FlashcardRepository flashcards,
                           CoachConversationRepository coachConversations, MasteryService masteryService,
                           EventBus eventBus) {
        this.courses = courses;
        this.materials = materials;
        this.questions = questions;
        this.quizzes = quizzes;
        this.attempts = attempts;
        this.flashcards = flashcards;
        this.coachConversations = coachConversations;
        this.masteryService = masteryService;
        eventBus.subscribe(this);
    }

    @Override
    public void onStudyEvent(StudyEvent event) {
        if (event.type() == StudyEvent.Type.COACH_CONVERSATION_STARTED) {
            coachConversations.add(event.courseId(), new CoachConversationRepository.Conversation(
                    shorten(event.message()), event.occurredAt()));
        }
    }

    /** The summary, mastery and full history of the course. */
    public ProgressReport getProgress(long courseId) {
        Course course = courses.findById(courseId)
                .orElseThrow(() -> new NoSuchElementException("No course with id " + courseId));
        List<QuizAttempt> graded = submittedAttempts(courseId).stream()
                .filter(a -> a.getStatus() == AttemptStatus.GRADED).toList();
        double average = graded.stream().mapToDouble(QuizAttempt::getPercentage).average().orElse(0);
        return new ProgressReport(course.getName(), graded.size(), average,
                currentStreak(studyDays(courseId), LocalDate.now()), masteryService.computeMastery(courseId),
                getHistory(courseId, null, null, null));
    }

    /**
     * The activity of the course, newest first.
     *
     * @param from first day to include, or null
     * @param to   last day to include, or null
     * @param type the kind of activity to include, or null for all
     * @throws IllegalArgumentException if from is after to
     */
    public List<ActivityRecord> getHistory(long courseId, LocalDate from, LocalDate to, ActivityType type) {
        if (from != null && to != null && from.isAfter(to)) {
            throw new IllegalArgumentException("The start date must not be after the end date.");
        }
        List<ActivityRecord> history = new ArrayList<>();
        history.addAll(materialRecords(courseId));
        history.addAll(questionRecords(courseId));
        history.addAll(quizRecords(courseId));
        history.addAll(flashcardRecords(courseId));
        history.addAll(coachRecords(courseId));
        return history.stream()
                .filter(r -> type == null || r.type() == type)
                .filter(r -> from == null || !r.occurredAt().toLocalDate().isBefore(from))
                .filter(r -> to == null || !r.occurredAt().toLocalDate().isAfter(to))
                .sorted(Comparator.comparing(ActivityRecord::occurredAt).reversed())
                .toList();
    }

    /**
     * Writes the full report to a file, replacing any file already there.
     *
     * @throws IllegalStateException if the file cannot be written
     */
    public Path exportReport(long courseId, ReportFormat format, Path file) {
        ProgressReport report = getProgress(courseId);
        String text = format == ReportFormat.CSV ? ProgressReportWriter.toCsv(report)
                : ProgressReportWriter.toMarkdown(report);
        try {
            return Files.writeString(file, text);
        } catch (IOException e) {
            throw new IllegalStateException("Could not write the report to " + file + ". Choose another location.", e);
        }
    }

    /** Consecutive study days ending today, or yesterday if there has been no study today yet. */
    static int currentStreak(Set<LocalDate> studyDays, LocalDate today) {
        LocalDate day = studyDays.contains(today) ? today : today.minusDays(1);
        int streak = 0;
        while (studyDays.contains(day)) {
            streak++;
            day = day.minusDays(1);
        }
        return streak;
    }

    /** Days on which the student actually studied. Uploading or generating something does not count. */
    private Set<LocalDate> studyDays(long courseId) {
        Set<LocalDate> days = new HashSet<>();
        submittedAttempts(courseId).forEach(a -> days.add(a.getSubmittedAt().toLocalDate()));
        flashcards.findReviews(courseId).forEach(r -> days.add(r.reviewedAt().toLocalDate()));
        questions.findByCourse(courseId).forEach(q -> days.add(q.askedAt().toLocalDate()));
        coachConversations.findByCourse(courseId).forEach(c -> days.add(c.startedAt().toLocalDate()));
        return days;
    }

    private List<QuizAttempt> submittedAttempts(long courseId) {
        return attempts.findByCourse(courseId).stream().filter(a -> a.getSubmittedAt() != null).toList();
    }

    private List<ActivityRecord> materialRecords(long courseId) {
        return materials.findByCourse(courseId).stream()
                .map(m -> new ActivityRecord(m.getAddedAt(), ActivityType.MATERIAL, "Material uploaded",
                        m.getTitle() + ", " + count(m.getPageCount(), "page"), ""))
                .toList();
    }

    private List<ActivityRecord> questionRecords(long courseId) {
        return questions.findByCourse(courseId).stream()
                .map(q -> new ActivityRecord(q.askedAt(), ActivityType.QUESTION, "Question asked",
                        oneLine(q.question()), ""))
                .toList();
    }

    private List<ActivityRecord> quizRecords(long courseId) {
        Map<Long, Quiz> quizById = quizzes.findByCourse(courseId).stream()
                .collect(Collectors.toMap(Quiz::getId, quiz -> quiz));
        List<ActivityRecord> records = new ArrayList<>();
        quizById.values().forEach(quiz -> records.add(new ActivityRecord(quiz.getCreatedAt(), ActivityType.QUIZ,
                "Quiz created", quizDetails(quiz), "")));
        submittedAttempts(courseId).forEach(attempt -> records.add(new ActivityRecord(attempt.getSubmittedAt(),
                ActivityType.QUIZ, "Quiz taken", quizDetails(quizById.get(attempt.getQuizId())), quizResult(attempt))));
        return records;
    }

    private static String quizDetails(Quiz quiz) {
        return count(quiz.size(), "question") + ", " + quiz.getDifficulty().name().toLowerCase(Locale.ROOT);
    }

    private static String quizResult(QuizAttempt attempt) {
        long pending = attempt.getResults().values().stream().filter(GradeResult::pending).count();
        long disputed = attempt.getResults().values().stream().filter(GradeResult::overridden).count();
        List<String> notes = new ArrayList<>();
        if (pending > 0) {
            notes.add(pending + " pending");
        }
        if (disputed > 0) {
            notes.add(disputed + " disputed");
        }
        String score = String.format("%.0f%%", attempt.getPercentage());
        return notes.isEmpty() ? score : score + " (" + String.join(", ", notes) + ")";
    }

    private List<ActivityRecord> flashcardRecords(long courseId) {
        List<ActivityRecord> records = new ArrayList<>();
        Map<Long, List<Flashcard>> decks = flashcards.findByCourse(courseId).stream()
                .collect(Collectors.groupingBy(Flashcard::getDeckId, LinkedHashMap::new, Collectors.toList()));
        decks.values().forEach(deck -> records.add(new ActivityRecord(deck.get(0).getCreatedAt(),
                ActivityType.FLASHCARDS, "Deck created", count(deck.size(), "card"), "")));
        Map<LocalDate, List<FlashcardReview>> reviewsByDay = flashcards.findReviews(courseId).stream()
                .collect(Collectors.groupingBy(r -> r.reviewedAt().toLocalDate()));
        reviewsByDay.values().forEach(reviews -> records.add(reviewRecord(reviews)));
        return records;
    }

    /** One row for a day's reviews, dated at the last of them, which are stored oldest first. */
    private static ActivityRecord reviewRecord(List<FlashcardReview> reviews) {
        Map<Rating, Integer> ratings = new EnumMap<>(Rating.class);
        reviews.forEach(review -> ratings.merge(review.rating(), 1, Integer::sum));
        String result = ratings.entrySet().stream()
                .map(e -> e.getValue() + " " + e.getKey().name().toLowerCase(Locale.ROOT))
                .collect(Collectors.joining(", "));
        LocalDateTime last = reviews.get(reviews.size() - 1).reviewedAt();
        return new ActivityRecord(last, ActivityType.FLASHCARDS, "Cards reviewed", count(reviews.size(), "review"),
                result);
    }

    private List<ActivityRecord> coachRecords(long courseId) {
        return coachConversations.findByCourse(courseId).stream()
                .map(c -> new ActivityRecord(c.startedAt(), ActivityType.COACH, "Coach conversation", c.firstMessage(),
                        ""))
                .toList();
    }

    private static String count(int amount, String noun) {
        return amount + " " + noun + (amount == 1 ? "" : "s");
    }

    private static String oneLine(String text) {
        return text.strip().replaceAll("\\s+", " ");
    }

    private static String shorten(String text) {
        String line = oneLine(text);
        return line.length() <= MAX_COACH_LINE_LENGTH ? line : line.substring(0, MAX_COACH_LINE_LENGTH - 3) + "...";
    }
}
