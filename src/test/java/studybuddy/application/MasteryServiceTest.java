package studybuddy.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import studybuddy.ai.FakeLLMClient;
import studybuddy.ai.LLMException;
import studybuddy.ai.ResponseParser;
import studybuddy.domain.Concept;
import studybuddy.domain.ConceptMastery;
import studybuddy.domain.Course;
import studybuddy.domain.Difficulty;
import studybuddy.domain.Flashcard;
import studybuddy.domain.GradeResult;
import studybuddy.domain.Quiz;
import studybuddy.domain.QuizAttempt;
import studybuddy.domain.Rating;
import studybuddy.domain.TrueFalseQuestion;
import studybuddy.domain.Trend;
import studybuddy.persistence.AttemptRepository;
import studybuddy.persistence.ConceptRepository;
import studybuddy.persistence.CourseRepository;
import studybuddy.persistence.Database;
import studybuddy.persistence.FlashcardRepository;
import studybuddy.persistence.QuizRepository;

/** F09 with real repositories. Attempts are stored directly, so the only scripted model call is the diagnosis. */
class MasteryServiceTest {

    private Database db;
    private FakeLLMClient llm;
    private MasteryService service;
    private QuizRepository quizzes;
    private AttemptRepository attempts;
    private ConceptRepository concepts;
    private FlashcardRepository cards;
    private long courseId;
    private long facade;
    private long observer;

    @BeforeEach
    void setUp() {
        db = Database.inMemory();
        llm = new FakeLLMClient();
        quizzes = new QuizRepository(db);
        attempts = new AttemptRepository(db);
        concepts = new ConceptRepository(db);
        courseId = new CourseRepository(db).save(new Course(0, "EECS3311", "")).getId();
        facade = concepts.save(new Concept(0, courseId, "Facade", "", 3)).getId();
        observer = concepts.save(new Concept(0, courseId, "Observer", "", 3)).getId();
        cards = new FlashcardRepository(db);
        service = new MasteryService(attempts, quizzes, concepts, cards, llm, new ResponseParser(), 60);
    }

    /** Stores a card on the concept with one rating at the given time. */
    private void rate(Long conceptId, Rating rating, LocalDateTime at) {
        Flashcard card = Flashcard.create(courseId, 0, conceptId, "Front", "Back", 1, LocalDate.of(2026, 11, 1));
        cards.saveDeck(List.of(card));
        card.review(rating);
        cards.recordReview(card, rating, at);
    }

    @Test
    void flashcardRatingsAreEvidence() {
        LocalDateTime start = LocalDateTime.of(2026, 11, 1, 9, 0);
        rate(facade, Rating.AGAIN, start);
        rate(facade, Rating.AGAIN, start.plusMinutes(1));
        rate(facade, Rating.AGAIN, start.plusMinutes(2));
        rate(observer, Rating.EASY, start);
        rate(observer, Rating.GOOD, start.plusMinutes(1));
        rate(observer, Rating.EASY, start.plusMinutes(2));

        assertTrue(masteryOf(facade).weak());
        assertEquals(0.0, masteryOf(facade).score());
        assertEquals(3, masteryOf(facade).evidenceCount());
        assertFalse(masteryOf(observer).weak());
    }

    @Test
    void untaggedCardsAreExcluded() {
        rate(null, Rating.AGAIN, LocalDateTime.of(2026, 11, 1, 9, 0));

        assertTrue(service.computeMastery(courseId).stream().allMatch(m -> m.evidenceCount() == 0));
    }

    @Test
    void quizAnswersAndRatingsAreOrderedByTime() {
        // the old rating happened before the quiz, so it counts less: 0.9 for the rating, 1 for the answer
        rate(facade, Rating.AGAIN, LocalDateTime.of(2020, 1, 1, 9, 0));
        answer(facade, Difficulty.EASY, 1);

        assertEquals(100 * 1 / 1.9, masteryOf(facade).score(), 0.001);
        assertEquals(Trend.UP, masteryOf(facade).trend());
    }

    @Test
    void weakFromRatingsAloneStillGetsADiagnosis() {
        LocalDateTime start = LocalDateTime.of(2026, 11, 1, 9, 0);
        rate(facade, Rating.AGAIN, start);
        rate(facade, Rating.AGAIN, start.plusMinutes(1));
        rate(facade, Rating.AGAIN, start.plusMinutes(2));
        llm.enqueueText("{\"patterns\": [\"You struggle to recall Facade.\"], \"actions\": []}");

        assertTrue(service.diagnose(courseId).contains("You struggle to recall Facade."));
        assertTrue(prompts().contains("these scores come from low flashcard ratings"));
    }

    @AfterEach
    void tearDown() {
        db.close();
    }

    /** Stores one submitted attempt with one question per score, in order, all on the same concept. */
    private void answer(Long conceptId, Difficulty difficulty, double... scores) {
        Quiz quiz = new Quiz(0, courseId, LocalDateTime.now(), difficulty);
        for (int i = 0; i < scores.length; i++) {
            quiz.addQuestion(new TrueFalseQuestion(0, "Statement " + i, difficulty, 1, conceptId, null, true));
        }
        quizzes.save(quiz);
        QuizAttempt attempt = QuizAttempt.start(quiz);
        attempt.submit();
        for (int i = 0; i < scores.length; i++) {
            attempt.recordResult(GradeResult.byAI(quiz.getQuestions().get(i).getId(), scores[i], ""));
        }
        attempts.save(attempt);
    }

    /** One attempt per score, so that each is newer than the one before. */
    private void answerSeparately(long conceptId, Difficulty difficulty, double... scores) {
        for (double score : scores) {
            answer(conceptId, difficulty, score);
        }
    }

    private ConceptMastery masteryOf(long conceptId) {
        return service.computeMastery(courseId).stream().filter(m -> m.conceptId() == conceptId).findFirst()
                .orElseThrow();
    }

    @Test
    void conceptsWithoutAnswersHaveNoEvidence() {
        List<ConceptMastery> mastery = service.computeMastery(courseId);

        assertEquals(2, mastery.size());
        assertTrue(mastery.stream().allMatch(m -> m.evidenceCount() == 0 && m.insufficientData() && !m.weak()));
        assertEquals(Trend.FLAT, mastery.get(0).trend());
    }

    @Test
    void fewerThanThreeAnswersIsNeverWeak() {
        answerSeparately(facade, Difficulty.EASY, 0, 0);

        ConceptMastery mastery = masteryOf(facade);

        assertEquals(2, mastery.evidenceCount());
        assertEquals(0.0, mastery.score());
        assertFalse(mastery.weak());
        assertEquals("Not enough data", mastery.statusLabel());
    }

    @Test
    void scoreBelowTheThresholdIsWeak() {
        answerSeparately(facade, Difficulty.EASY, 0, 0, 0);
        answerSeparately(observer, Difficulty.EASY, 1, 1, 1);

        assertEquals("Weak", masteryOf(facade).statusLabel());
        assertEquals("Strong", masteryOf(observer).statusLabel());
        assertEquals(List.of(facade), service.getWeakConcepts(courseId).stream().map(ConceptMastery::conceptId).toList());
    }

    @Test
    void scoreOfExactlyTheThresholdIsNotWeak() {
        answerSeparately(facade, Difficulty.EASY, 0.6, 0.6, 0.6);

        assertFalse(masteryOf(facade).weak());
    }

    @Test
    void newerAnswersCountMore() {
        answerSeparately(facade, Difficulty.EASY, 0, 1);

        // weights: older 0.9, newer 1
        assertEquals(100 * 1 / 1.9, masteryOf(facade).score(), 0.001);
    }

    @Test
    void harderQuestionsCountMore() {
        answer(facade, Difficulty.EASY, 0);
        answer(facade, Difficulty.HARD, 1);

        // weights: easy 1 * 0.9, hard 2
        assertEquals(100 * 2 / 2.9, masteryOf(facade).score(), 0.001);
    }

    @Test
    void trendComparesTheNewerHalfWithTheOlderHalf() {
        answerSeparately(facade, Difficulty.EASY, 0, 0, 1, 1);
        answerSeparately(observer, Difficulty.EASY, 1, 1, 0, 0);
        assertEquals(Trend.UP, masteryOf(facade).trend());
        assertEquals(Trend.DOWN, masteryOf(observer).trend());
    }

    @Test
    void smallChangesAreAFlatTrend() {
        answerSeparately(facade, Difficulty.EASY, 0.5, 0.55);

        assertEquals(Trend.FLAT, masteryOf(facade).trend());
    }

    @Test
    void ranksWeakestFirstThenConceptsWithoutEnoughEvidence() {
        long adapter = concepts.save(new Concept(0, courseId, "Adapter", "", 3)).getId();
        long builder = concepts.save(new Concept(0, courseId, "Builder", "", 3)).getId();
        answerSeparately(facade, Difficulty.EASY, 1, 1, 1);
        answerSeparately(observer, Difficulty.EASY, 0, 0, 0);
        answerSeparately(builder, Difficulty.EASY, 1);

        assertEquals(List.of(observer, facade, builder, adapter),
                service.computeMastery(courseId).stream().map(ConceptMastery::conceptId).toList());
    }

    @Test
    void untaggedAnswersAreExcluded() {
        answer(null, Difficulty.EASY, 0, 0, 0);

        assertTrue(service.computeMastery(courseId).stream().allMatch(m -> m.evidenceCount() == 0));
    }

    @Test
    void pendingAnswersAreExcludedAndOverridesCount() {
        Quiz quiz = new Quiz(0, courseId, LocalDateTime.now(), Difficulty.EASY);
        quiz.addQuestion(new TrueFalseQuestion(0, "One", Difficulty.EASY, 1, facade, null, true));
        quiz.addQuestion(new TrueFalseQuestion(0, "Two", Difficulty.EASY, 1, facade, null, true));
        quizzes.save(quiz);
        QuizAttempt attempt = QuizAttempt.start(quiz);
        attempt.submit();
        long first = quiz.getQuestions().get(0).getId();
        attempt.recordResult(GradeResult.byAI(first, 0, "").overrideWith(1));
        attempt.recordResult(GradeResult.pending(quiz.getQuestions().get(1).getId()));
        attempts.save(attempt);

        ConceptMastery mastery = masteryOf(facade);

        assertEquals(1, mastery.evidenceCount());
        assertEquals(100.0, mastery.score());
    }

    @Test
    void mergedConceptsCombineTheirEvidenceAndDeletedOnesLoseIt() {
        answerSeparately(facade, Difficulty.EASY, 1, 1);
        answerSeparately(observer, Difficulty.EASY, 1);
        concepts.merge(observer, facade);
        assertEquals(3, masteryOf(facade).evidenceCount());

        concepts.delete(facade);
        assertTrue(service.computeMastery(courseId).isEmpty());
    }

    @Test
    void masteryIsPerCourse() {
        answerSeparately(facade, Difficulty.EASY, 0, 0, 0);
        long other = new CourseRepository(db).save(new Course(0, "Other", "")).getId();

        assertTrue(service.computeMastery(other).isEmpty());
    }

    // ----- diagnosis -----

    private List<String> stems() {
        List<String> stems = new ArrayList<>();
        quizzes.findByCourse(courseId).forEach(q -> q.getQuestions().forEach(x -> stems.add(x.getStem())));
        return stems;
    }

    @Test
    void diagnosisSendsWeakConceptsAndWrongAnswers() {
        answerSeparately(facade, Difficulty.EASY, 0, 0, 1);
        answerSeparately(observer, Difficulty.EASY, 0, 1, 1);
        llm.enqueueText("{\"patterns\": [\"You confuse Facade with Adapter.\"], \"actions\": [\"Compare their intent.\"]}");

        String diagnosis = service.diagnose(courseId);

        assertEquals("Patterns:\n- You confuse Facade with Adapter.\n\nWhat to practise:\n- Compare their intent.",
                diagnosis);
        String prompt = llm.lastRequest().messages().get(0).text();
        assertTrue(prompt.contains("Facade: mastery"), prompt);
        assertTrue(prompt.contains("Concept: Facade"), prompt);
        assertTrue(prompt.contains("Correct answer: True"), prompt);
        assertFalse(prompt.contains("Concept: Observer"), prompt);
        assertFalse(prompts().contains("Observer: mastery"));
    }

    private String prompts() {
        return llm.lastRequest().messages().get(0).text();
    }

    @Test
    void diagnosisLimitsTheWrongAnswersSent() {
        answerSeparately(facade, Difficulty.EASY, new double[MasteryService.MAX_WRONG_ANSWERS_IN_PROMPT + 5]);
        llm.enqueueText("{\"patterns\": [\"A pattern\"], \"actions\": []}");

        service.diagnose(courseId);

        assertEquals(MasteryService.MAX_WRONG_ANSWERS_IN_PROMPT + 1, prompts().split("Concept: ").length);
        assertEquals(MasteryService.MAX_WRONG_ANSWERS_IN_PROMPT + 5, stems().size());
    }

    @Test
    void noWeakConceptsMeansNoModelCall() {
        answerSeparately(facade, Difficulty.EASY, 1, 1, 1);

        assertTrue(service.diagnose(courseId).contains("no weak concepts"));
        assertEquals(0, llm.getRequests().size());
    }

    @Test
    void modelFailureDoesNotAffectTheScores() {
        answerSeparately(facade, Difficulty.EASY, 0, 0, 0);
        llm.enqueueFailure(new LLMException("rate limited", true));

        assertThrows(ContentGenerationException.class, () -> service.diagnose(courseId));
        assertTrue(masteryOf(facade).weak());
    }

    @Test
    void emptyDiagnosisIsAnError() {
        answerSeparately(facade, Difficulty.EASY, 0, 0, 0);
        llm.enqueueText("{\"patterns\": [], \"actions\": []}");

        assertThrows(ContentGenerationException.class, () -> service.diagnose(courseId));
    }
}
