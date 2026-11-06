package studybuddy.application;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.stream.Collectors;

import studybuddy.ai.LLMClient;
import studybuddy.ai.LLMException;
import studybuddy.ai.PromptBuilder;
import studybuddy.ai.ResponseFormatException;
import studybuddy.ai.ResponseParser;
import studybuddy.ai.RetrievalStrategy;
import studybuddy.domain.Chunk;
import studybuddy.domain.Concept;
import studybuddy.domain.Difficulty;
import studybuddy.domain.GradeResult;
import studybuddy.domain.Question;
import studybuddy.domain.QuestionType;
import studybuddy.domain.Quiz;
import studybuddy.domain.QuizAttempt;
import studybuddy.domain.QuizRequest;
import studybuddy.grading.GradingStrategy;
import studybuddy.persistence.AttemptRepository;
import studybuddy.persistence.ConceptRepository;
import studybuddy.persistence.MaterialRepository;
import studybuddy.persistence.QuizRepository;

/**
 * Generates quizzes (F06) and grades attempts (F07).
 * The LLM writes the questions and grades short answers. Everything around it is deterministic: scope,
 * validation of each question, avoiding repeats, the choice of grader, and the totals.
 */
public class QuizService {

    /** F06: invalid questions are regenerated up to two times, then dropped. */
    static final int MAX_REGENERATIONS = 2;
    /** Below this many words per question there is not enough material for the requested count. */
    static final int WORDS_PER_QUESTION = 20;
    private static final int RECENT_QUIZZES = 5;
    private static final int RECENT_STEMS_IN_PROMPT = 30;

    private static final String QUESTIONS_SCHEMA = """
            {"questions": [
              {"type": "mcq", "stem": "the question", "options": ["first", "second", "third", "fourth"],
               "correctIndex": 0, "excerpt": 1, "page": 3, "concept": "Observer pattern"},
              {"type": "tf", "stem": "a statement", "answer": true, "excerpt": 2, "page": 5, "concept": null},
              {"type": "short", "stem": "the question", "referenceAnswer": "a model answer",
               "rubric": "what a full answer must mention", "excerpt": 2, "page": 6, "concept": null}]}""";

    private final LLMClient llm;
    private final ResponseParser responseParser;
    private final QuestionFactory questionFactory;
    private final Map<QuestionType, GradingStrategy> graders;
    private final QuizRepository quizRepo;
    private final AttemptRepository attemptRepo;
    private final StudyScope scope;
    private final EventBus eventBus;

    /** Shape of the model's JSON reply: question fields differ by type, so each one is a map. */
    record QuestionsJson(List<Map<String, Object>> questions) {
    }

    /**
     * @param graders    the grading strategy for each question type
     * @param topK       number of excerpts retrieved per concept when the scope is a set of concepts
     * @param wordBudget most words of material text sent in one prompt
     */
    public QuizService(LLMClient llm, RetrievalStrategy retriever, ResponseParser responseParser,
                       QuestionFactory questionFactory, Map<QuestionType, GradingStrategy> graders,
                       QuizRepository quizRepo, AttemptRepository attemptRepo, MaterialRepository materialRepo,
                       ConceptRepository conceptRepo, EventBus eventBus, int topK, int wordBudget) {
        this.llm = llm;
        this.responseParser = responseParser;
        this.questionFactory = questionFactory;
        this.graders = Map.copyOf(graders);
        this.quizRepo = quizRepo;
        this.attemptRepo = attemptRepo;
        this.scope = new StudyScope(materialRepo, conceptRepo, retriever, topK, wordBudget);
        this.eventBus = eventBus;
    }

    // ----- F06 generation -----

    /**
     * Generates and saves a quiz. The result can be shorter than requested if the material is too short
     * or some questions stayed invalid; callers compare its size with the requested count to tell the student.
     *
     * @throws IllegalStateException      if the scope contains no text
     * @throws ContentGenerationException if the model fails or no valid question could be produced; nothing is saved
     */
    public Quiz generateQuiz(QuizRequest request) {
        if (request.adaptive()) {
            throw new IllegalArgumentException("Adaptive quizzes are not available yet; choose a difficulty.");
        }
        List<Concept> concepts = scope.concepts(request.courseId(), request.conceptIds());
        List<Chunk> excerpts = scope.excerpts(request.courseId(), request.materialIds(), request.conceptIds(), concepts);
        int words = excerpts.stream().mapToInt(Chunk::wordCount).sum();
        int count = Math.min(request.questionCount(), Math.max(1, words / WORDS_PER_QUESTION));

        Map<QuestionType, Integer> missing = splitAcrossTypes(count, request.types());
        List<String> recentStems = quizRepo.findRecentStems(request.courseId(), RECENT_QUIZZES);
        Set<String> usedStems = recentStems.stream().map(StudyScope::normalizeText)
                .collect(Collectors.toCollection(HashSet::new));
        List<Question> accepted = new ArrayList<>();
        try {
            for (int round = 0; round <= MAX_REGENERATIONS && !missing.isEmpty(); round++) {
                List<String> avoid = new ArrayList<>(accepted.stream().map(Question::getStem).toList());
                recentStems.stream().limit(RECENT_STEMS_IN_PROMPT).forEach(avoid::add);
                QuestionsJson reply = requestQuestions(missing, request, excerpts, concepts, avoid);
                for (Map<String, Object> json : reply.questions() == null
                        ? List.<Map<String, Object>>of() : reply.questions()) {
                    accept(json, request, excerpts, concepts, missing, usedStems, accepted);
                }
            }
        } catch (LLMException | ResponseFormatException e) {
            throw new ContentGenerationException("The quiz could not be generated: " + e.getMessage(), List.of(), e);
        }
        if (accepted.isEmpty()) {
            throw new ContentGenerationException("The quiz could not be generated: the model did not produce any "
                    + "valid new questions.", List.of(), null);
        }

        Quiz quiz = new Quiz(0, request.courseId(), LocalDateTime.now(), request.difficulty());
        accepted.forEach(quiz::addQuestion);
        quizRepo.save(quiz);
        eventBus.publish(StudyEvent.of(StudyEvent.Type.QUIZ_CREATED, request.courseId(),
                "Created a quiz with " + quiz.size() + " question(s)"));
        return quiz;
    }

    public Quiz getQuiz(long quizId) {
        return quizRepo.findById(quizId)
                .orElseThrow(() -> new NoSuchElementException("No quiz with id " + quizId));
    }

    /** Most recent first. */
    public List<Quiz> listQuizzes(long courseId) {
        return quizRepo.findByCourse(courseId);
    }

    /** Adds the question to the accepted list if it is valid, still needed and not a repeat. */
    private void accept(Map<String, Object> json, QuizRequest request, List<Chunk> excerpts, List<Concept> concepts,
                        Map<QuestionType, Integer> missing, Set<String> usedStems, List<Question> accepted) {
        Question question = toQuestion(json, request, excerpts, concepts);
        if (question == null || !question.isValid() || !missing.containsKey(question.getType())) {
            return;
        }
        if (usedStems.add(StudyScope.normalizeText(question.getStem()))) {
            accepted.add(question);
            missing.computeIfPresent(question.getType(), (type, left) -> left == 1 ? null : left - 1);
        }
    }

    /** The question described by the reply, or null if its type, excerpt or concept does not check out. */
    private Question toQuestion(Map<String, Object> json, QuizRequest request, List<Chunk> excerpts,
                                List<Concept> concepts) {
        if (json == null || !(json.get("excerpt") instanceof Number excerpt)
                || excerpt.intValue() < 1 || excerpt.intValue() > excerpts.size()) {
            return null;
        }
        Chunk chunk = excerpts.get(excerpt.intValue() - 1);
        Map<String, Object> data = new HashMap<>(json);
        data.put("difficulty", request.difficulty());
        data.put("sourcePage", StudyScope.pageWithin(chunk, json.get("page")));
        data.put("sourceChunkId", chunk.getId());
        String conceptName = json.get("concept") instanceof String name ? name.strip() : "";
        Long conceptId = null;
        if (!conceptName.isEmpty()) {
            Concept concept = concepts.stream().filter(c -> c.matches(conceptName)).findFirst().orElse(null);
            if (concept == null) {
                return null;
            }
            conceptId = concept.getId();
        } else if (!request.conceptIds().isEmpty()) {
            return null;
        }
        data.put("conceptId", conceptId);
        try {
            QuestionType type = QuestionType.fromCode(String.valueOf(json.get("type")));
            return questionFactory.createQuestion(type, data);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private QuestionsJson requestQuestions(Map<QuestionType, Integer> missing, QuizRequest request,
                                           List<Chunk> excerpts, List<Concept> concepts, List<String> avoid) {
        PromptBuilder prompt = new PromptBuilder()
                .system("You are StudyBuddy, a study assistant. You write quiz questions that test a student's "
                        + "understanding of their course materials.")
                .withInstruction("Base every question only on the numbered excerpts. \"excerpt\" is the number of "
                        + "the excerpt the question comes from and \"page\" is a page that excerpt covers.")
                .withInstruction("Multiple-choice questions have exactly 4 different options, one of them correct; "
                        + "\"correctIndex\" counts from 0. Vary the position of the correct option.")
                .withInstruction("True/false questions state a claim; \"answer\" is the JSON boolean true or false.")
                .withInstruction("Short-answer questions need a model \"referenceAnswer\" and a \"rubric\" listing "
                        + "the points a full answer must mention.")
                .withInstruction(difficultyGuidance(request.difficulty()))
                .withOutputSchema(QUESTIONS_SCHEMA)
                .withExcerpts(excerpts);
        if (!concepts.isEmpty()) {
            prompt.withInstruction("\"concept\" is the concept the question tests, written exactly as one of these "
                    + "names: " + concepts.stream().map(Concept::getName).collect(Collectors.joining("; ")) + ". "
                    + (request.conceptIds().isEmpty() ? "Use null only if none fits."
                            : "Every question must test one of these concepts; ignore other topics in the excerpts."));
        } else {
            prompt.withInstruction("Set \"concept\" to null.");
        }
        if (!avoid.isEmpty()) {
            prompt.withInstruction("Do not repeat or reword these existing questions: "
                    + String.join(" | ", avoid));
        }
        return responseParser.requestJson(llm, prompt.withUserMessage("Write exactly " + describe(missing) + ".")
                .build(), QuestionsJson.class);
    }

    private static String difficultyGuidance(Difficulty difficulty) {
        return switch (difficulty) {
            case EASY -> "Difficulty is easy: test recall of definitions and basic facts.";
            case MEDIUM -> "Difficulty is medium: test understanding and applying ideas to simple situations.";
            case HARD -> "Difficulty is hard: test analysis, comparing ideas and reasoning about tricky cases.";
        };
    }

    private static String describe(Map<QuestionType, Integer> counts) {
        return counts.entrySet().stream()
                .map(e -> e.getValue() + " question(s) of type \"" + e.getKey().code() + "\"")
                .collect(Collectors.joining(", "));
    }

    /** Divides the questions evenly over the types, e.g. 10 over three types gives 4, 3 and 3. */
    static Map<QuestionType, Integer> splitAcrossTypes(int count, Set<QuestionType> types) {
        List<QuestionType> ordered = types.stream().sorted().toList();
        Map<QuestionType, Integer> counts = new EnumMap<>(QuestionType.class);
        for (int i = 0; i < count; i++) {
            counts.merge(ordered.get(i % ordered.size()), 1, Integer::sum);
        }
        return counts;
    }

    // ----- F07 grading -----

    /** A new, unsaved attempt at the quiz; a quiz can be retaken any number of times. */
    public QuizAttempt startAttempt(long quizId) {
        return QuizAttempt.start(getQuiz(quizId));
    }

    /**
     * Locks the answers, grades each one with the strategy for its question type and saves the attempt.
     * A short answer the model could not grade stays pending; see {@link #regradePending}.
     *
     * @throws IllegalStateException if this attempt was already submitted
     */
    public QuizAttempt submitAttempt(QuizAttempt attempt) {
        Quiz quiz = getQuiz(attempt.getQuizId());
        attempt.submit();
        for (Question question : quiz.getQuestions()) {
            attempt.recordResult(gradeAnswer(question, attempt.answerFor(question.getId())));
        }
        attemptRepo.save(attempt);
        publishSubmitted(attempt);
        return attempt;
    }

    private GradeResult gradeAnswer(Question question, String answer) {
        return graders.get(question.getType()).grade(question, answer);
    }

    public QuizAttempt getAttempt(long attemptId) {
        return attemptRepo.findById(attemptId)
                .orElseThrow(() -> new NoSuchElementException("No quiz attempt with id " + attemptId));
    }

    /** Most recent first. */
    public List<QuizAttempt> listAttempts(long courseId) {
        return attemptRepo.findByCourse(courseId);
    }

    /** Tries again to grade the answers that are waiting for the model; any that fail stay pending. */
    public QuizAttempt regradePending(long attemptId) {
        QuizAttempt attempt = getAttempt(attemptId);
        Quiz quiz = getQuiz(attempt.getQuizId());
        for (Question question : quiz.getQuestions()) {
            boolean pending = attempt.resultFor(question.getId()).map(GradeResult::pending).orElse(false);
            if (pending) {
                attempt.recordResult(gradeAnswer(question, attempt.answerFor(question.getId())));
            }
        }
        attemptRepo.save(attempt);
        publishSubmitted(attempt);
        return attempt;
    }

    /**
     * Dispute: the student replaces an AI-assigned grade; the result is recorded as overridden.
     *
     * @param score the new score, from 0 to 1
     * @throws IllegalStateException if the answer has no completed AI grade
     */
    public QuizAttempt overrideGrade(long attemptId, long questionId, double score) {
        if (Double.isNaN(score) || score < 0 || score > 1) {
            throw new IllegalArgumentException("The score must be between 0 and 1.");
        }
        QuizAttempt attempt = getAttempt(attemptId);
        GradeResult current = attempt.resultFor(questionId).orElseThrow(
                () -> new NoSuchElementException("Question " + questionId + " is not part of this attempt"));
        attempt.recordResult(current.overrideWith(score));
        attemptRepo.save(attempt);
        publishSubmitted(attempt);
        return attempt;
    }

    private void publishSubmitted(QuizAttempt attempt) {
        eventBus.publish(StudyEvent.of(StudyEvent.Type.QUIZ_SUBMITTED, attempt.getCourseId(), String.format(
                "Scored %.1f/%d on quiz #%d", attempt.getTotalScore(), attempt.getMaxScore(), attempt.getQuizId())));
    }
}
