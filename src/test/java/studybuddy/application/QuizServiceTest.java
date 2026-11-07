package studybuddy.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import studybuddy.ai.DocumentParser;
import studybuddy.ai.FakeLLMClient;
import studybuddy.ai.KeywordRetriever;
import studybuddy.ai.LLMException;
import studybuddy.ai.ResponseParser;
import studybuddy.domain.AttemptStatus;
import studybuddy.domain.Concept;
import studybuddy.domain.Difficulty;
import studybuddy.domain.GradeResult;
import studybuddy.domain.Material;
import studybuddy.domain.MultipleChoiceQuestion;
import studybuddy.domain.Question;
import studybuddy.domain.QuestionType;
import studybuddy.domain.Quiz;
import studybuddy.domain.QuizAttempt;
import studybuddy.domain.QuizRequest;
import studybuddy.domain.ShortAnswerQuestion;
import studybuddy.domain.TrueFalseQuestion;
import studybuddy.grading.LLMRubricGrader;
import studybuddy.persistence.AttemptRepository;
import studybuddy.persistence.ConceptRepository;
import studybuddy.persistence.CourseRepository;
import studybuddy.persistence.Database;
import studybuddy.persistence.MaterialRepository;
import studybuddy.persistence.QuizRepository;

/** F05 and F06 with a scripted model and a real in-memory database. */
class QuizServiceTest {

    private static final String MCQ = """
            {"type": "mcq", "stem": "Which pattern hides a subsystem?", "options": ["Facade", "Observer", "Builder", "Command"],
             "correctIndex": 0, "excerpt": 1, "page": 1, "concept": null}""";
    private static final String TF = """
            {"type": "tf", "stem": "An Adapter converts an interface.", "answer": true, "excerpt": 1, "page": 1, "concept": null}""";
    private static final String SHORT = """
            {"type": "short", "stem": "What does a Facade offer?", "referenceAnswer": "One simple interface.",
             "rubric": "Mentions a simple interface.", "excerpt": 1, "page": 1, "concept": null}""";

    @TempDir
    Path dir;

    private Database db;
    private FakeLLMClient llm;
    private QuizService service;
    private ConceptRepository concepts;
    private final List<StudyEvent> events = new ArrayList<>();
    private long courseId;
    private Material material;

    @BeforeAll
    static void silenceExpectedWarnings() {
        Logger.getLogger(LLMRubricGrader.class.getName()).setLevel(Level.OFF);
    }

    @BeforeEach
    void setUp() throws IOException {
        db = Database.inMemory();
        llm = new FakeLLMClient();
        EventBus bus = new EventBus();
        bus.subscribe(events::add);
        KeywordRetriever retriever = new KeywordRetriever();
        MaterialRepository materials = new MaterialRepository(db);
        MaterialService materialService = new MaterialService(new CourseRepository(db), materials,
                new DocumentParser(), retriever, bus, 1_000_000, 400, 50);
        courseId = materialService.createCourse("EECS3311", "").getId();
        material = materialService.uploadMaterial(courseId, Files.writeString(dir.resolve("notes.txt"),
                "The Facade pattern gives one simple interface to a whole subsystem. ".repeat(20)));
        concepts = new ConceptRepository(db);
        ResponseParser parser = new ResponseParser();
        service = new QuizService(llm, retriever, parser, new QuestionFactory(),
                ApplicationFactory.createGraders(llm, parser), new QuizRepository(db), new AttemptRepository(db),
                materials, concepts, bus, 6, 6000);
        events.clear();
    }

    @AfterEach
    void tearDown() {
        db.close();
    }

    private static String reply(String... questions) {
        return "{\"questions\": [" + String.join(",", questions) + "]}";
    }

    private QuizRequest request(int count, QuestionType... types) {
        return new QuizRequest(courseId, List.of(), List.of(), count, Set.of(types), Difficulty.HARD);
    }

    private String lastPrompt() {
        return llm.lastRequest().systemText() + "\n" + llm.lastRequest().messages().get(0).text();
    }

    // ----- F05 generation -----

    @Test
    void generatesAndSavesAQuizWithEveryType() {
        llm.enqueueText(reply(MCQ, TF, SHORT));

        Quiz quiz = service.generateQuiz(request(3, QuestionType.MULTIPLE_CHOICE, QuestionType.TRUE_FALSE,
                QuestionType.SHORT_ANSWER));

        assertEquals(3, quiz.size());
        assertInstanceOf(MultipleChoiceQuestion.class, quiz.getQuestions().get(0));
        assertInstanceOf(TrueFalseQuestion.class, quiz.getQuestions().get(1));
        assertInstanceOf(ShortAnswerQuestion.class, quiz.getQuestions().get(2));
        Question first = service.getQuiz(quiz.getId()).getQuestions().get(0);
        assertEquals(Difficulty.HARD, first.getDifficulty());
        assertEquals(material.getChunks().get(0).getId(), first.getSourceChunkId());
        assertEquals(1, first.getSourcePage());
        assertEquals(StudyEvent.Type.QUIZ_CREATED, events.get(0).type());
        assertEquals(1, llm.getRequests().size());
        assertTrue(lastPrompt().contains("Difficulty is hard"));
        assertTrue(lastPrompt().contains("1 question(s) of type \"tf\""));
    }

    @Test
    void splitsQuestionsEvenlyAcrossTypes() {
        Map<QuestionType, Integer> split = QuizService.splitAcrossTypes(10, Set.of(QuestionType.MULTIPLE_CHOICE,
                QuestionType.TRUE_FALSE, QuestionType.SHORT_ANSWER));

        assertEquals(4, split.get(QuestionType.MULTIPLE_CHOICE));
        assertEquals(3, split.get(QuestionType.TRUE_FALSE));
        assertEquals(3, split.get(QuestionType.SHORT_ANSWER));
        assertEquals(1, QuizService.splitAcrossTypes(1, Set.of(QuestionType.SHORT_ANSWER, QuestionType.TRUE_FALSE)).size());
    }

    @Test
    void regeneratesInvalidQuestionsOnly() {
        String twoOptions = MCQ.replace("[\"Facade\", \"Observer\", \"Builder\", \"Command\"]", "[\"Facade\", \"Observer\"]");
        llm.enqueueText(reply(twoOptions, TF));
        llm.enqueueText(reply(MCQ));

        Quiz quiz = service.generateQuiz(request(2, QuestionType.MULTIPLE_CHOICE, QuestionType.TRUE_FALSE));

        assertEquals(2, quiz.size());
        assertEquals(2, llm.getRequests().size());
        assertTrue(lastPrompt().contains("1 question(s) of type \"mcq\""));
        assertFalse(lastPrompt().contains("question(s) of type \"tf\""));
        assertTrue(lastPrompt().contains("An Adapter converts an interface."));
    }

    @Test
    void dropsQuestionsStillInvalidAfterTwoRegenerations() {
        String stringAnswer = TF.replace("true", "\"true\"");
        llm.enqueueText(reply(MCQ, stringAnswer));
        llm.enqueueText(reply(stringAnswer));
        llm.enqueueText(reply(stringAnswer));

        Quiz quiz = service.generateQuiz(request(2, QuestionType.MULTIPLE_CHOICE, QuestionType.TRUE_FALSE));

        assertEquals(1, quiz.size());
        assertEquals(3, llm.getRequests().size());
    }

    @Test
    void rejectsQuestionsWithAnUnknownExcerpt() {
        String badExcerpt = TF.replace("\"excerpt\": 1", "\"excerpt\": 9");
        llm.enqueueText(reply(badExcerpt));
        llm.enqueueText(reply(TF));

        assertEquals(1, service.generateQuiz(request(1, QuestionType.TRUE_FALSE)).size());
        assertEquals(2, llm.getRequests().size());
    }

    @Test
    void fixesAPageOutsideTheExcerpt() {
        llm.enqueueText(reply(TF.replace("\"page\": 1", "\"page\": 99")));

        assertEquals(1, service.generateQuiz(request(1, QuestionType.TRUE_FALSE)).getQuestions().get(0).getSourcePage());
    }

    @Test
    void ignoresQuestionsOfTypesNotRequestedAndExtras() {
        llm.enqueueText(reply(MCQ, TF, TF.replace("An Adapter", "A Builder")));

        Quiz quiz = service.generateQuiz(request(1, QuestionType.TRUE_FALSE));

        assertEquals(1, quiz.size());
        assertInstanceOf(TrueFalseQuestion.class, quiz.getQuestions().get(0));
    }

    @Test
    void regeneratesQuestionsThatRepeatARecentQuiz() {
        llm.enqueueText(reply(TF));
        service.generateQuiz(request(1, QuestionType.TRUE_FALSE));
        llm.enqueueText(reply(TF.replace("An Adapter converts an interface.", "an adapter CONVERTS an interface!")));
        llm.enqueueText(reply(TF.replace("An Adapter", "A Builder")));

        Quiz quiz = service.generateQuiz(request(1, QuestionType.TRUE_FALSE));

        assertEquals("A Builder converts an interface.", quiz.getQuestions().get(0).getStem());
        assertEquals(3, llm.getRequests().size());
        assertTrue(lastPrompt().contains("Do not repeat"));
    }

    @Test
    void shortMaterialGivesAShorterQuiz() {
        // 220 words allow 11 questions; the request is for 30
        List<String> many = new ArrayList<>();
        for (int i = 0; i < 11; i++) {
            many.add(TF.replace("An Adapter", "Adapter " + i));
        }
        llm.enqueueText(reply(many.toArray(new String[0])));

        Quiz quiz = service.generateQuiz(request(30, QuestionType.TRUE_FALSE));

        assertEquals(1, llm.getRequests().size());
        assertEquals(11, quiz.size());
        assertTrue(lastPrompt().contains("11 question(s)"));
    }

    @Test
    void tagsQuestionsWithExistingConceptsAndRejectsUnknownOnes() {
        Concept facade = concepts.save(new Concept(0, courseId, "Facade", "Hides a subsystem.", 3));
        llm.enqueueText(reply(TF.replace("\"concept\": null", "\"concept\": \"Quantum physics\"")));
        llm.enqueueText(reply(TF.replace("\"concept\": null", "\"concept\": \"facade\"")));

        Quiz quiz = service.generateQuiz(request(1, QuestionType.TRUE_FALSE));

        assertEquals(facade.getId(), quiz.getQuestions().get(0).getConceptId());
        assertTrue(lastPrompt().contains("exactly as one of these names: Facade"));
    }

    @Test
    void conceptScopeRequiresATagAndRetrievesExcerpts() {
        Concept facade = concepts.save(new Concept(0, courseId, "Facade", "Hides a subsystem.", 3));
        llm.enqueueText(reply(TF));
        llm.enqueueText(reply(TF.replace("\"concept\": null", "\"concept\": \"Facade\"")));
        QuizRequest request = new QuizRequest(courseId, List.of(), List.of(facade.getId()), 1,
                Set.of(QuestionType.TRUE_FALSE), Difficulty.EASY);

        Quiz quiz = service.generateQuiz(request);

        assertEquals(facade.getId(), quiz.getQuestions().get(0).getConceptId());
        assertEquals(2, llm.getRequests().size());
        assertTrue(lastPrompt().contains("Every question must test one of these concepts"), lastPrompt());
    }

    @Test
    void materialScopeRejectsAnotherCoursesMaterial() {
        QuizRequest request = new QuizRequest(courseId + 1, List.of(material.getId()), List.of(), 1,
                Set.of(QuestionType.TRUE_FALSE), Difficulty.EASY);

        assertThrows(IllegalArgumentException.class, () -> service.generateQuiz(request));
    }

    @Test
    void conceptScopeRejectsAnotherCoursesConcept() {
        QuizRequest request = new QuizRequest(courseId, List.of(), List.of(999L), 1,
                Set.of(QuestionType.TRUE_FALSE), Difficulty.EASY);

        assertThrows(IllegalArgumentException.class, () -> service.generateQuiz(request));
    }

    @Test
    void courseWithoutTextIsRejected() {
        QuizRequest request = new QuizRequest(courseId + 1, List.of(), List.of(), 1,
                Set.of(QuestionType.TRUE_FALSE), Difficulty.EASY);

        assertThrows(IllegalStateException.class, () -> service.generateQuiz(request));
        assertEquals(0, llm.getRequests().size());
    }

    @Test
    void modelFailureSavesNothing() {
        llm.enqueueText(reply(TF));
        llm.enqueueFailure(new LLMException("rate limited", true));

        assertThrows(ContentGenerationException.class,
                () -> service.generateQuiz(request(2, QuestionType.TRUE_FALSE)));
        assertTrue(service.listQuizzes(courseId).isEmpty());
        assertTrue(events.isEmpty());
    }

    @Test
    void noValidQuestionIsAnError() {
        llm.enqueueText(reply());
        llm.enqueueText(reply());
        llm.enqueueText(reply());

        assertThrows(ContentGenerationException.class,
                () -> service.generateQuiz(request(1, QuestionType.TRUE_FALSE)));
        assertTrue(service.listQuizzes(courseId).isEmpty());
    }

    @Test
    void largeCourseStaysWithinTheWordBudget() throws IOException {
        KeywordRetriever retriever = new KeywordRetriever();
        MaterialRepository materials = new MaterialRepository(db);
        MaterialService small = new MaterialService(new CourseRepository(db), materials, new DocumentParser(),
                retriever, new EventBus(), 1_000_000, 10, 0);
        long course = small.createCourse("Big", "").getId();
        small.uploadMaterial(course, Files.writeString(dir.resolve("big.txt"), "word ".repeat(100)));
        ResponseParser parser = new ResponseParser();
        QuizService budgeted = new QuizService(llm, retriever, parser, new QuestionFactory(),
                ApplicationFactory.createGraders(llm, parser), new QuizRepository(db), new AttemptRepository(db),
                materials, concepts, new EventBus(), 6, 25);
        llm.enqueueText(reply(TF));

        budgeted.generateQuiz(new QuizRequest(course, List.of(), List.of(), 1, Set.of(QuestionType.TRUE_FALSE),
                Difficulty.EASY));

        assertEquals(2, llm.lastRequest().messages().get(0).text().split("\\[\\d\\] \\(page").length - 1);
    }

    // ----- F06 grading -----

    private Quiz threeQuestionQuiz() {
        llm.enqueueText(reply(MCQ, TF, SHORT));
        Quiz quiz = service.generateQuiz(request(3, QuestionType.MULTIPLE_CHOICE, QuestionType.TRUE_FALSE,
                QuestionType.SHORT_ANSWER));
        events.clear();
        return quiz;
    }

    private static long id(Quiz quiz, int index) {
        return quiz.getQuestions().get(index).getId();
    }

    @Test
    void gradesEachQuestionWithTheRightStrategyAndSaves() {
        Quiz quiz = threeQuestionQuiz();
        QuizAttempt attempt = service.startAttempt(quiz.getId());
        attempt.answer(id(quiz, 0), "A");
        attempt.answer(id(quiz, 1), "false");
        attempt.answer(id(quiz, 2), "A simple interface");
        llm.enqueueText("{\"score\": 0.5, \"feedback\": \"Partly.\"}");

        QuizAttempt graded = service.submitAttempt(attempt);

        assertEquals(AttemptStatus.GRADED, graded.getStatus());
        assertEquals(1.5, graded.getTotalScore());
        assertEquals(50.0, graded.getPercentage());
        assertFalse(graded.resultFor(id(quiz, 0)).orElseThrow().gradedByAI());
        assertTrue(graded.resultFor(id(quiz, 2)).orElseThrow().gradedByAI());
        assertEquals(StudyEvent.Type.QUIZ_SUBMITTED, events.get(0).type());
        QuizAttempt stored = service.getAttempt(graded.getId());
        assertEquals(1.5, stored.getTotalScore());
        assertEquals("Partly.", stored.resultFor(id(quiz, 2)).orElseThrow().feedback());
    }

    @Test
    void unansweredQuestionsScoreZeroWithoutAModelCall() {
        Quiz quiz = threeQuestionQuiz();
        int callsBefore = llm.getRequests().size();

        QuizAttempt graded = service.submitAttempt(service.startAttempt(quiz.getId()));

        assertEquals(0.0, graded.getTotalScore());
        assertEquals(AttemptStatus.GRADED, graded.getStatus());
        assertEquals(callsBefore, llm.getRequests().size());
    }

    @Test
    void answerToAnotherQuizsQuestionIsRejected() {
        Quiz quiz = threeQuestionQuiz();
        QuizAttempt attempt = service.startAttempt(quiz.getId());

        assertThrows(IllegalArgumentException.class, () -> attempt.answer(99_999, "A"));
    }

    @Test
    void secondSubmissionIsRejectedButARetakeIsAllowed() {
        Quiz quiz = threeQuestionQuiz();
        QuizAttempt first = service.submitAttempt(service.startAttempt(quiz.getId()));

        assertThrows(IllegalStateException.class, () -> service.submitAttempt(first));

        service.submitAttempt(service.startAttempt(quiz.getId()));
        assertEquals(2, service.listAttempts(courseId).size());
    }

    @Test
    void modelFailureLeavesTheShortAnswerPendingAndRegradeCompletesIt() {
        Quiz quiz = threeQuestionQuiz();
        QuizAttempt attempt = service.startAttempt(quiz.getId());
        attempt.answer(id(quiz, 0), "A");
        attempt.answer(id(quiz, 2), "A simple interface");
        llm.enqueueFailure(new LLMException("rate limited", true));

        QuizAttempt graded = service.submitAttempt(attempt);

        assertEquals(AttemptStatus.PENDING_GRADING, graded.getStatus());
        assertEquals(1.0, graded.getTotalScore());

        llm.enqueueFailure(new LLMException("still limited", true));
        assertEquals(AttemptStatus.PENDING_GRADING, service.regradePending(graded.getId()).getStatus());

        llm.enqueueText("{\"score\": 1.0, \"feedback\": \"Good.\"}");
        QuizAttempt done = service.regradePending(graded.getId());
        assertEquals(AttemptStatus.GRADED, done.getStatus());
        assertEquals(2.0, done.getTotalScore());
        assertEquals("A", done.answerFor(id(quiz, 0)));
    }

    @Test
    void disputeReplacesAnAiGradeAndIsRecorded() {
        Quiz quiz = threeQuestionQuiz();
        QuizAttempt attempt = service.startAttempt(quiz.getId());
        attempt.answer(id(quiz, 2), "A simple interface");
        llm.enqueueText("{\"score\": 0.25, \"feedback\": \"Thin.\"}");
        long attemptId = service.submitAttempt(attempt).getId();

        QuizAttempt changed = service.overrideGrade(attemptId, id(quiz, 2), 0.75);

        GradeResult result = service.getAttempt(changed.getId()).resultFor(id(quiz, 2)).orElseThrow();
        assertEquals(0.75, result.score());
        assertTrue(result.overridden());
        assertEquals("Thin.", result.feedback());
    }

    @Test
    void disputeIsOnlyForCompletedAiGradesWithAValidScore() {
        Quiz quiz = threeQuestionQuiz();
        long attemptId = service.submitAttempt(service.startAttempt(quiz.getId())).getId();

        assertThrows(IllegalStateException.class, () -> service.overrideGrade(attemptId, id(quiz, 0), 1.0));
        assertThrows(IllegalArgumentException.class, () -> service.overrideGrade(attemptId, id(quiz, 0), 1.5));
        assertThrows(java.util.NoSuchElementException.class, () -> service.overrideGrade(attemptId, 99_999, 0.5));
    }

    @Test
    void missingQuizAndAttemptGiveClearErrors() {
        assertThrows(java.util.NoSuchElementException.class, () -> service.getQuiz(99));
        assertThrows(java.util.NoSuchElementException.class, () -> service.getAttempt(99));
        assertNull(service.listQuizzes(courseId).stream().findFirst().orElse(null));
    }
}
