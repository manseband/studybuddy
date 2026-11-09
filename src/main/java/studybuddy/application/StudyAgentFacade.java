package studybuddy.application;

import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.function.Consumer;

import studybuddy.agent.AgentController;
import studybuddy.domain.ActivityRecord;
import studybuddy.domain.ActivityType;
import studybuddy.domain.AgentResponse;
import studybuddy.domain.Answer;
import studybuddy.domain.Concept;
import studybuddy.domain.ConceptMastery;
import studybuddy.domain.Course;
import studybuddy.domain.Explanation;
import studybuddy.domain.Flashcard;
import studybuddy.domain.GradeResult;
import studybuddy.domain.Material;
import studybuddy.domain.ProgressReport;
import studybuddy.domain.QaEntry;
import studybuddy.domain.Question;
import studybuddy.domain.Quiz;
import studybuddy.domain.QuizAttempt;
import studybuddy.domain.QuizRequest;
import studybuddy.domain.Rating;
import studybuddy.domain.ReportFormat;
import studybuddy.domain.TraceStep;

/**
 * Facade: the single entry point used by the GUI and the CLI. It hides the services, repositories,
 * AI clients and retrievers behind a small set of use-case methods, so the presentation layer never
 * depends on them directly. Services for later features are added here as they are implemented.
 */
public class StudyAgentFacade implements AutoCloseable {

    private final MaterialService materialService;
    private final LearningContentService contentService;
    private final QuizService quizService;
    private final MasteryService masteryService;
    private final FlashcardService flashcardService;
    private final ProgressService progressService;
    private final AgentController agentController;
    private final EventBus eventBus;
    private final boolean aiAvailable;
    private final AutoCloseable resources;

    /**
     * @param aiAvailable true when a Gemini API key is configured
     * @param resources   closed by {@link #close()} (the database)
     */
    public StudyAgentFacade(MaterialService materialService, LearningContentService contentService,
                            QuizService quizService, MasteryService masteryService,
                            FlashcardService flashcardService, ProgressService progressService,
                            AgentController agentController, EventBus eventBus, boolean aiAvailable,
                            AutoCloseable resources) {
        this.materialService = materialService;
        this.contentService = contentService;
        this.quizService = quizService;
        this.masteryService = masteryService;
        this.flashcardService = flashcardService;
        this.progressService = progressService;
        this.agentController = agentController;
        this.eventBus = eventBus;
        this.aiAvailable = aiAvailable;
        this.resources = resources;
    }

    // ----- F01 courses and materials -----

    public Course createCourse(String code, String name) {
        return materialService.createCourse(code, name);
    }

    public List<Course> listCourses() {
        return materialService.listCourses();
    }

    public Course findCourse(String idOrCode) {
        return materialService.findCourse(idOrCode);
    }

    public void deleteCourse(long courseId) {
        materialService.deleteCourse(courseId);
    }

    public Material uploadMaterial(long courseId, Path file) {
        return materialService.uploadMaterial(courseId, file);
    }

    public Material uploadMaterial(long courseId, Path file, boolean replaceDuplicate) {
        return materialService.uploadMaterial(courseId, file, replaceDuplicate);
    }

    public List<Material> listMaterials(long courseId) {
        return materialService.listMaterials(courseId);
    }

    public Material renameMaterial(long materialId, String newTitle) {
        return materialService.renameMaterial(materialId, newTitle);
    }

    public void removeMaterial(long materialId) {
        materialService.removeMaterial(materialId);
    }

    /** Retries embeddings for chunks that have none (e.g. after a rate limit during upload). */
    public MaterialService.EmbeddingRefresh refreshEmbeddings(long courseId) {
        return materialService.refreshEmbeddings(courseId);
    }

    // ----- F03 questions -----

    public Answer askQuestion(long courseId, String question) {
        return contentService.answerQuestion(courseId, question);
    }

    public Answer askQuestion(long courseId, String question, boolean allowGeneralKnowledge) {
        return contentService.answerQuestion(courseId, question, allowGeneralKnowledge);
    }

    public List<QaEntry> getQuestionHistory(long courseId) {
        return contentService.getQuestionHistory(courseId);
    }

    // ----- F02 concepts -----

    /** Candidate concepts for review; nothing is saved until {@link #saveConcepts}. */
    public List<Concept> extractConcepts(long materialId) {
        return contentService.extractConcepts(materialId);
    }

    public List<Concept> saveConcepts(long materialId, List<Concept> concepts) {
        return contentService.saveConcepts(materialId, concepts);
    }

    public List<Concept> listConcepts(long courseId) {
        return contentService.listConcepts(courseId);
    }

    public Concept getConcept(long conceptId) {
        return contentService.getConcept(conceptId);
    }

    public List<Material> getConceptMaterials(long conceptId) {
        return contentService.getConceptMaterials(conceptId);
    }

    public Concept updateConcept(long conceptId, String name, String definition, Integer importance) {
        return contentService.updateConcept(conceptId, name, definition, importance);
    }

    public Concept mergeConcepts(long fromId, long intoId) {
        return contentService.mergeConcepts(fromId, intoId);
    }

    public void deleteConcept(long conceptId) {
        contentService.deleteConcept(conceptId);
    }

    // ----- F05, F06 quizzes -----

    /** The quiz can be shorter than requested if there is too little material; compare its size. */
    public Quiz generateQuiz(QuizRequest request) {
        return quizService.generateQuiz(request);
    }

    public Quiz getQuiz(long quizId) {
        return quizService.getQuiz(quizId);
    }

    public List<Quiz> listQuizzes(long courseId) {
        return quizService.listQuizzes(courseId);
    }

    public QuizAttempt startAttempt(long quizId) {
        return quizService.startAttempt(quizId);
    }

    public QuizAttempt submitQuiz(QuizAttempt attempt) {
        return quizService.submitAttempt(attempt);
    }

    public QuizAttempt getAttempt(long attemptId) {
        return quizService.getAttempt(attemptId);
    }

    public List<QuizAttempt> listAttempts(long courseId) {
        return quizService.listAttempts(courseId);
    }

    public QuizAttempt regradePending(long attemptId) {
        return quizService.regradePending(attemptId);
    }

    /** A changed grade makes any explanation written for the old one out of date, so it is forgotten. */
    public QuizAttempt overrideGrade(long attemptId, long questionId, double score) {
        QuizAttempt attempt = quizService.overrideGrade(attemptId, questionId, score);
        contentService.clearExplanation(attemptId, questionId);
        return attempt;
    }

    // ----- F07 explanations -----

    /**
     * Explains a graded answer, using the cached explanation if there is one.
     *
     * @throws NoSuchElementException     if the question is not part of the attempt
     * @throws IllegalStateException      if the answer is still waiting to be graded
     * @throws ContentGenerationException if the model fails
     */
    public Explanation explainAnswer(long attemptId, long questionId) {
        QuizAttempt attempt = quizService.getAttempt(attemptId);
        Question question = quizService.getQuiz(attempt.getQuizId()).findQuestion(questionId).orElseThrow(
                () -> new NoSuchElementException("Question " + questionId + " is not part of this attempt"));
        GradeResult result = attempt.resultFor(questionId).orElseThrow(
                () -> new IllegalStateException("This answer has not been graded yet."));
        if (result.pending()) {
            throw new IllegalStateException("This answer has not been graded yet.");
        }
        return contentService.explainAnswer(attemptId, question, attempt.answerFor(questionId), result.correct());
    }

    // ----- F04 flashcards -----

    /** Unsaved cards for the student to review; there can be fewer than requested. Save with {@link #saveFlashcards}. */
    public List<Flashcard> generateFlashcards(long courseId, List<Long> materialIds, List<Long> conceptIds, int count) {
        return flashcardService.generateCards(courseId, materialIds, conceptIds, count);
    }

    public List<Flashcard> saveFlashcards(List<Flashcard> cards) {
        return flashcardService.saveDeck(cards);
    }

    public List<Flashcard> listFlashcards(long courseId) {
        return flashcardService.listCards(courseId);
    }

    public List<Flashcard> getDeck(long deckId) {
        return flashcardService.getDeck(deckId);
    }

    /** Deletes the deck's cards; mastery, review history and the streak are kept. */
    public void deleteDeck(long deckId) {
        flashcardService.deleteDeck(deckId);
    }

    public List<Flashcard> getDueCards(long courseId) {
        return flashcardService.getDueCards(courseId);
    }

    public List<Flashcard> getDueCardsInDeck(long deckId) {
        return flashcardService.getDueCardsInDeck(deckId);
    }

    /** When the next of these cards is due, or empty if there are no cards. */
    public Optional<LocalDate> getNextDueDate(List<Flashcard> cards) {
        return flashcardService.nextDueDate(cards);
    }

    public Flashcard reviewFlashcard(long cardId, Rating rating) {
        return flashcardService.recordReview(cardId, rating);
    }

    // ----- F08 weak concepts -----

    /** The mastery of every concept in the course, weakest first. */
    public List<ConceptMastery> getMastery(long courseId) {
        return masteryService.computeMastery(courseId);
    }

    public List<ConceptMastery> getWeakConcepts(long courseId) {
        return masteryService.getWeakConcepts(courseId);
    }

    public String diagnoseWeakConcepts(long courseId) {
        return masteryService.diagnose(courseId);
    }

    // ----- F09 Study Coach -----

    /**
     * Sends a message to the Study Coach. This can take a while, so call it off the Swing thread.
     *
     * @param courseId the selected course, or null
     * @param onStep   hears each step start and finish, on the calling thread
     */
    public AgentResponse askCoach(Long courseId, String message, Consumer<TraceStep> onStep) {
        return agentController.handleMessage(courseId, message, onStep);
    }

    public AgentResponse askCoach(Long courseId, String message) {
        return askCoach(courseId, message, step -> { });
    }

    /** Starts a new conversation with the Study Coach. Stated preferences are kept. */
    public void resetCoach() {
        agentController.resetConversation();
    }

    // ----- F10 progress and history -----

    public ProgressReport getProgress(long courseId) {
        return progressService.getProgress(courseId);
    }

    /**
     * The course's activity, newest first. Each filter can be null.
     *
     * @throws IllegalArgumentException if from is after to
     */
    public List<ActivityRecord> getHistory(long courseId, LocalDate from, LocalDate to, ActivityType type) {
        return progressService.getHistory(courseId, from, to, type);
    }

    /**
     * Writes the full progress report to a file, replacing any file already there.
     *
     * @throws IllegalStateException if the file cannot be written
     */
    public Path exportReport(long courseId, ReportFormat format, Path file) {
        return progressService.exportReport(courseId, format, file);
    }

    // ----- general -----

    public void addListener(StudyEventListener listener) {
        eventBus.subscribe(listener);
    }

    /** False when no API key is configured; AI features then explain how to add one. */
    public boolean isAiAvailable() {
        return aiAvailable;
    }

    @Override
    public void close() {
        try {
            resources.close();
        } catch (Exception e) {
            throw new IllegalStateException("Could not close StudyBuddy cleanly", e);
        }
    }
}
