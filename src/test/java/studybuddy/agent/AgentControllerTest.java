package studybuddy.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import studybuddy.ai.DocumentParser;
import studybuddy.ai.FakeLLMClient;
import studybuddy.ai.KeywordRetriever;
import studybuddy.ai.LLMException;
import studybuddy.ai.LLMMessage;
import studybuddy.ai.LLMRequest;
import studybuddy.ai.LLMResponse;
import studybuddy.ai.NoKeyLLMClient;
import studybuddy.ai.ResponseParser;
import studybuddy.ai.ToolCall;
import studybuddy.application.ApplicationFactory;
import studybuddy.application.EventBus;
import studybuddy.application.FlashcardService;
import studybuddy.application.LearningContentService;
import studybuddy.application.MasteryService;
import studybuddy.application.MaterialService;
import studybuddy.application.QuestionFactory;
import studybuddy.application.QuizService;
import studybuddy.application.StudyEvent;
import studybuddy.domain.AgentResponse;
import studybuddy.domain.Concept;
import studybuddy.domain.CreatedItem;
import studybuddy.domain.Material;
import studybuddy.domain.TraceStep;
import studybuddy.persistence.AttemptRepository;
import studybuddy.persistence.ConceptRepository;
import studybuddy.persistence.CourseRepository;
import studybuddy.persistence.Database;
import studybuddy.persistence.ExplanationRepository;
import studybuddy.persistence.FlashcardRepository;
import studybuddy.persistence.MaterialRepository;
import studybuddy.persistence.PreferenceRepository;
import studybuddy.persistence.QaHistoryRepository;
import studybuddy.persistence.QuizRepository;

/** F12 through the real tools and services with a scripted model. */
class AgentControllerTest {

    @TempDir
    Path dir;

    private Database db;
    private FakeLLMClient llm;
    private EventBus bus;
    private MaterialService materials;
    private LearningContentService content;
    private QuizService quizzes;
    private MasteryService mastery;
    private FlashcardService flashcards;
    private PreferenceRepository preferences;
    private AgentController agent;
    private long courseId;
    private Material material;
    private final List<StudyEvent> events = new ArrayList<>();

    @BeforeEach
    void setUp() throws IOException {
        db = Database.inMemory();
        llm = new FakeLLMClient();
        bus = new EventBus();
        KeywordRetriever retriever = new KeywordRetriever();
        ResponseParser parser = new ResponseParser();
        MaterialRepository materialRepo = new MaterialRepository(db);
        ConceptRepository concepts = new ConceptRepository(db);
        QuizRepository quizRepo = new QuizRepository(db);
        AttemptRepository attempts = new AttemptRepository(db);
        FlashcardRepository cards = new FlashcardRepository(db);
        materials = new MaterialService(new CourseRepository(db), materialRepo, new DocumentParser(), retriever, bus,
                1_000_000, 400, 50);
        content = new LearningContentService(llm, retriever, parser, materialRepo, new QaHistoryRepository(db), new ExplanationRepository(db),
                concepts, bus, 6, 6000);
        quizzes = new QuizService(llm, retriever, parser, new QuestionFactory(),
                ApplicationFactory.createGraders(llm, parser), quizRepo, attempts, materialRepo, concepts, bus, 6, 6000);
        mastery = new MasteryService(attempts, quizRepo, concepts, cards, llm, parser, 60);
        flashcards = new FlashcardService(llm, retriever, parser, cards, materialRepo, concepts, bus, 6, 6000);
        preferences = new PreferenceRepository(db);
        agent = agentWithLimit(8);

        courseId = materials.createCourse("EECS3311", "Software Design").getId();
        material = materials.uploadMaterial(courseId, Files.writeString(dir.resolve("patterns.txt"),
                "The Facade pattern gives one simple interface to a whole subsystem. ".repeat(20)));
        concepts.save(new Concept(0, courseId, "Facade", "Hides a subsystem.", 3));
        bus.subscribe(events::add);
    }

    @AfterEach
    void tearDown() {
        db.close();
    }

    private AgentController agentWithLimit(int maxSteps) {
        return ApplicationFactory.createAgent(llm, materials, content, quizzes, mastery, flashcards, preferences, bus,
                maxSteps);
    }

    private static ToolCall call(String name, Map<String, Object> args) {
        return new ToolCall("id-" + name, name, args);
    }

    private void scriptCall(String name, Map<String, Object> args) {
        llm.enqueue(LLMResponse.toolCalls(call(name, args)));
    }

    private AgentResponse ask(String message) {
        return agent.handleMessage(courseId, message, step -> { });
    }

    /** The result the model was sent for the last tool call. */
    private String lastToolResult() {
        List<LLMMessage> messages = llm.lastRequest().messages();
        return messages.get(messages.size() - 1).text();
    }

    // ----- the loop -----

    @Test
    void aFinalAnswerNeedsNoTools() {
        llm.enqueueText("  Hello! What would you like to study?  ");

        AgentResponse response = ask("hi");

        assertEquals("Hello! What would you like to study?", response.text());
        assertEquals(AgentResponse.Status.COMPLETED, response.status());
        assertTrue(response.steps().isEmpty());
        assertEquals(StudyEvent.Type.COACH_RESPONDED, events.get(0).type());
    }

    private long conversationsAnnounced() {
        return events.stream().filter(e -> e.type() == StudyEvent.Type.COACH_CONVERSATION_STARTED).count();
    }

    @Test
    void eachConversationIsAnnouncedOnceForTheHistory() {
        llm.enqueueText("One.");
        llm.enqueueText("Two.");
        llm.enqueueText("Three.");

        ask("first message");
        ask("second message");
        assertEquals(1, conversationsAnnounced());
        assertEquals("first message", events.stream()
                .filter(e -> e.type() == StudyEvent.Type.COACH_CONVERSATION_STARTED).findFirst().orElseThrow().message());

        agent.resetConversation();
        ask("a new conversation");
        assertEquals(2, conversationsAnnounced());
    }

    @Test
    void aConversationWithoutACourseHasNoPlaceInTheHistory() {
        materials.createCourse("Second course", "");
        llm.enqueueText("Which course?");

        agent.handleMessage(null, "hi", step -> { });

        assertEquals(0, conversationsAnnounced());
    }

    @Test
    void offersEveryToolToTheModelAndNothingThatDeletesOrChangesData() {
        llm.enqueueText("Done.");

        ask("hi");

        List<String> tools = llm.lastRequest().tools().stream().map(tool -> tool.name()).toList();
        assertEquals(List.of("search_materials", "get_weak_concepts", "generate_quiz", "generate_flashcards",
                "save_preference"), tools);
    }

    @Test
    void runsAToolAndSendsItsResultBack() {
        scriptCall("search_materials", Map.of("courseId", courseId, "query", "Facade subsystem"));
        llm.enqueueText("A Facade gives one simple interface (patterns.txt, p. 1).");
        List<TraceStep> seen = new ArrayList<>();

        AgentResponse response = agent.handleMessage(courseId, "What is a Facade?", seen::add);

        assertEquals("A Facade gives one simple interface (patterns.txt, p. 1).", response.text());
        assertEquals(1, response.steps().size());
        TraceStep step = response.steps().get(0);
        assertEquals("search_materials", step.tool());
        assertEquals(TraceStep.Status.SUCCEEDED, step.status());
        assertEquals("Found 1 excerpt(s)", step.result());
        assertTrue(lastToolResult().contains("patterns.txt"), lastToolResult());
        assertTrue(lastToolResult().contains("one simple interface"), lastToolResult());

        // the listener hears the step start and then finish, as it happens
        assertEquals(List.of(TraceStep.Status.RUNNING, TraceStep.Status.SUCCEEDED),
                seen.stream().map(TraceStep::status).toList());
        assertEquals("Searching materials for \"Facade subsystem\"", seen.get(0).description());
    }

    @Test
    void handlesSeveralCallsInOneReply() {
        llm.enqueue(LLMResponse.toolCalls(call("get_weak_concepts", Map.of("courseId", courseId)),
                call("search_materials", Map.of("courseId", courseId, "query", "Facade"))));
        llm.enqueueText("Done.");

        AgentResponse response = ask("Help");

        assertEquals(2, response.steps().size());
        long toolMessages = llm.lastRequest().messages().stream().filter(m -> m.role() == LLMMessage.Role.TOOL).count();
        assertEquals(2, toolMessages);
    }

    @Test
    void stopsAtTheStepLimitAndSummarizes() {
        agent = agentWithLimit(2);
        scriptCall("get_weak_concepts", Map.of("courseId", courseId));
        scriptCall("get_weak_concepts", Map.of("courseId", courseId));
        llm.enqueueText("I checked your concepts twice but did not finish.");

        AgentResponse response = ask("Do a lot");

        assertEquals(AgentResponse.Status.STEP_LIMIT, response.status());
        assertEquals(2, response.steps().size());
        assertEquals("I checked your concepts twice but did not finish.", response.text());
        LLMRequest summary = llm.lastRequest();
        assertTrue(summary.tools().isEmpty());
        assertTrue(summary.lastUserText().contains("cannot call more tools"));
        assertEquals(3, llm.getRequests().size());
    }

    @Test
    void callsBeyondTheLimitInOneReplyAreNotRunButAreAnswered() {
        agent = agentWithLimit(1);
        llm.enqueue(LLMResponse.toolCalls(new ToolCall("a", "get_weak_concepts", Map.of("courseId", courseId)),
                new ToolCall("b", "save_preference", Map.of("preference", "short answers"))));
        llm.enqueueText("Stopped.");

        AgentResponse response = ask("Do both");

        assertEquals(1, response.steps().size());
        assertTrue(preferences.findRecent(5).isEmpty());
        long answers = llm.lastRequest().messages().stream().filter(m -> m.role() == LLMMessage.Role.TOOL).count();
        assertEquals(2, answers);
    }

    @Test
    void ifTheSummaryFailsItIsMadeFromTheTrace() {
        agent = agentWithLimit(1);
        scriptCall("get_weak_concepts", Map.of("courseId", courseId));
        llm.enqueueFailure(new LLMException("down", true));

        AgentResponse response = ask("Do a lot");

        assertEquals(AgentResponse.Status.STEP_LIMIT, response.status());
        assertTrue(response.text().contains("Completed: 0 weak concept(s) out of 1"), response.text());
    }

    // ----- bad calls -----

    @Test
    void unknownToolIsNotRunAndTheModelCanCorrectItself() {
        scriptCall("delete_course", Map.of("courseId", courseId));
        llm.enqueueText("I cannot delete courses.");

        AgentResponse response = ask("Delete my course");

        assertEquals(TraceStep.Status.FAILED, response.steps().get(0).status());
        assertTrue(lastToolResult().contains("no tool named"), lastToolResult());
        assertEquals(1, materials.listCourses().size());
    }

    @Test
    void invalidArgumentsAreNotRun() {
        scriptCall("generate_flashcards", Map.of("courseId", courseId, "count", 99));
        llm.enqueueText("Sorry.");

        AgentResponse response = ask("Make 99 cards");

        assertEquals(TraceStep.Status.FAILED, response.steps().get(0).status());
        assertTrue(lastToolResult().contains("between 5 and 20"), lastToolResult());
        assertTrue(flashcards.listCards(courseId).isEmpty());
    }

    @Test
    void stopsAfterTwoRetriesOfBadCalls() {
        scriptCall("nope", Map.of());
        scriptCall("nope", Map.of());
        scriptCall("nope", Map.of());
        llm.enqueueText("I could not do that.");

        AgentResponse response = ask("Do it");

        assertEquals(AgentResponse.Status.STEP_LIMIT, response.status());
        assertEquals(3, response.steps().size());
        assertEquals(4, llm.getRequests().size());
    }

    @Test
    void aGoodCallResetsTheRetryCount() {
        scriptCall("nope", Map.of());
        scriptCall("nope", Map.of());
        scriptCall("get_weak_concepts", Map.of("courseId", courseId));
        scriptCall("nope", Map.of());
        llm.enqueueText("Finished.");

        assertEquals(AgentResponse.Status.COMPLETED, ask("Do it").status());
    }

    @Test
    void aFailingToolIsReportedToTheModelAndTraced() {
        // quiz generation fails because the course has no text
        long empty = materials.createCourse("Empty", "").getId();
        scriptCall("generate_quiz", Map.of("courseId", empty));
        llm.enqueueText("I could not create the quiz because the course has no materials.");

        AgentResponse response = ask("Quiz me");

        assertEquals(TraceStep.Status.FAILED, response.steps().get(0).status());
        assertTrue(lastToolResult().contains("no text"), lastToolResult());
        assertTrue(response.created().isEmpty());
        assertEquals(AgentResponse.Status.COMPLETED, response.status());
    }

    // ----- the model failing -----

    @Test
    void anUnavailableModelGivesAFailedResponse() {
        llm.enqueueFailure(new LLMException("rate limited", true));

        AgentResponse response = ask("Help");

        assertEquals(AgentResponse.Status.FAILED, response.status());
        assertTrue(response.text().contains("rate limited"));
        assertTrue(events.isEmpty());
    }

    @Test
    void workDoneBeforeTheModelFailedIsNotLost() {
        scriptCall("generate_flashcards", Map.of("courseId", courseId, "count", 5));
        llm.enqueueText("{\"cards\": [{\"front\": \"Front\", \"back\": \"Back\", \"excerpt\": 1, \"page\": 1, \"concept\": null}]}");
        llm.enqueueFailure(new LLMException("rate limited", true));

        AgentResponse response = ask("Make cards");

        assertEquals(AgentResponse.Status.FAILED, response.status());
        assertEquals(1, response.created().size());
        assertTrue(response.text().contains("Completed: Created Deck #"), response.text());
    }

    @Test
    void withoutAKeyTheStudentIsToldHowToAddOne() {
        AgentController noKey = ApplicationFactory.createAgent(new NoKeyLLMClient(), materials, content, quizzes, mastery,
                flashcards, preferences, bus, 8);

        AgentResponse response = noKey.handleMessage(courseId, "Help", step -> { });

        assertEquals(AgentResponse.Status.FAILED, response.status());
        assertTrue(response.text().contains("GEMINI_API_KEY"), response.text());
    }

    @Test
    void anEmptyReplyGetsAPoliteAnswer() {
        llm.enqueueText("   ");

        assertTrue(ask("Help").text().contains("rephrasing"));
    }

    // ----- tools use the real services -----

    @Test
    void quizToolCreatesAQuizWithoutShowingTheAnswers() {
        scriptCall("generate_quiz", Map.of("courseId", courseId, "count", 1, "types", List.of("mcq"), "difficulty", "hard"));
        llm.enqueueText("""
                {"questions": [{"type": "mcq", "stem": "Which hides a subsystem?",
                  "options": ["Facade", "Observer", "Builder", "Command"], "correctIndex": 0, "excerpt": 1, "page": 1,
                  "concept": "Facade"}]}""");
        llm.enqueueText("I made quiz 1.");

        AgentResponse response = ask("Quiz me");

        CreatedItem item = response.created().get(0);
        assertEquals(CreatedItem.Type.QUIZ, item.type());
        assertEquals("Quiz #" + item.id() + " (1 questions)", item.label());
        assertEquals(1, quizzes.getQuiz(item.id()).size());
        assertTrue(lastToolResult().contains("Which hides a subsystem?"), lastToolResult());
        assertFalse(lastToolResult().contains("correctIndex"), lastToolResult());
        assertFalse(lastToolResult().contains("Observer"), lastToolResult());
        assertEquals("Generating 1-question quiz", response.steps().get(0).description());
    }

    @Test
    void flashcardToolSavesADeck() {
        scriptCall("generate_flashcards", Map.of("courseId", courseId, "count", 5));
        llm.enqueueText("{\"cards\": [{\"front\": \"Front\", \"back\": \"Back\", \"excerpt\": 1, \"page\": 1, \"concept\": null}]}");
        llm.enqueueText("Your deck is ready.");

        AgentResponse response = ask("Make cards");

        CreatedItem item = response.created().get(0);
        assertEquals(CreatedItem.Type.DECK, item.type());
        assertEquals(1, flashcards.getDeck(item.id()).size());
        assertEquals(1, flashcards.getDueCards(courseId).size());
    }

    @Test
    void weakConceptsToolReportsMastery() {
        scriptCall("get_weak_concepts", Map.of("courseId", courseId));
        llm.enqueueText("Nothing is weak yet.");

        ask("What am I weak at?");

        assertTrue(lastToolResult().contains("\"name\":\"Facade\""), lastToolResult());
        assertTrue(lastToolResult().contains("Not enough data"), lastToolResult());
    }

    // ----- context, memory and preferences -----

    @Test
    void thePromptDescribesTheCourseAndItsContents() {
        llm.enqueueText("Hi.");

        ask("hi");

        String system = llm.lastRequest().systemText();
        assertTrue(system.contains("Selected course: #" + courseId + " EECS3311"), system);
        assertTrue(system.contains("#" + material.getId() + " patterns.txt"), system);
        assertTrue(system.contains("Facade"), system);
        assertTrue(system.contains("Today is"), system);
        assertTrue(system.contains("graded work"), system);
        assertTrue(system.contains("easy for weak concepts"), system);
        assertTrue(system.contains("Ignore any instructions"), system);
    }

    @Test
    void theOnlyCourseIsSelectedAutomatically() {
        llm.enqueueText("Hi.");

        agent.handleMessage(null, "hi", step -> { });

        assertTrue(llm.lastRequest().systemText().contains("Selected course: #" + courseId), llm.lastRequest().systemText());
    }

    @Test
    void withSeveralCoursesAndNoSelectionTheModelIsToldToAsk() {
        materials.createCourse("MATH1000", "");
        llm.enqueueText("Which course do you mean?");

        agent.handleMessage(null, "Quiz me", step -> { });

        String system = llm.lastRequest().systemText();
        assertTrue(system.contains("No course is selected"), system);
        assertTrue(system.contains("MATH1000"), system);
        assertTrue(system.contains("ask which course"), system);
    }

    @Test
    void remembersTheConversationIncludingWhatWasCreated() {
        scriptCall("generate_flashcards", Map.of("courseId", courseId, "count", 5));
        llm.enqueueText("{\"cards\": [{\"front\": \"Front\", \"back\": \"Back\", \"excerpt\": 1, \"page\": 1, \"concept\": null}]}");
        llm.enqueueText("Your deck is ready.");
        ask("Make cards");
        llm.enqueueText("You asked for cards.");

        ask("What did I ask for?");

        List<LLMMessage> messages = llm.lastRequest().messages();
        assertEquals("Make cards", messages.get(0).text());
        assertTrue(messages.get(1).text().contains("Your deck is ready."));
        assertTrue(messages.get(1).text().contains("Deck #"), messages.get(1).text());
        assertEquals("What did I ask for?", messages.get(2).text());
        assertEquals(3, messages.size());
    }

    @Test
    void resetForgetsTheConversationButNotPreferences() {
        scriptCall("save_preference", Map.of("preference", "prefers short answers"));
        llm.enqueueText("Noted.");
        ask("I prefer short answers from now on");

        agent.resetConversation();
        llm.enqueueText("Hi.");
        ask("hi");

        assertEquals(1, llm.lastRequest().messages().size());
        assertTrue(llm.lastRequest().systemText().contains("prefers short answers"));
    }

    @Test
    void preferencesLastAcrossSessions() {
        scriptCall("save_preference", Map.of("preference", "prefers short answers"));
        llm.enqueueText("Noted.");
        ask("I prefer short answers");

        AgentController nextSession = agentWithLimit(8);
        llm.enqueueText("Hi.");
        nextSession.handleMessage(courseId, "hi", step -> { });

        assertTrue(llm.lastRequest().systemText().contains("prefers short answers"));
    }

    @Test
    void rejectsEmptyAndTooLongMessages() {
        assertThrows(IllegalArgumentException.class, () -> ask("  "));
        assertThrows(IllegalArgumentException.class, () -> ask("x".repeat(AgentController.MAX_MESSAGE_LENGTH + 1)));
        assertEquals(0, llm.getRequests().size());
    }
}
