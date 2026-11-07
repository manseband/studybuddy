package studybuddy.agent;

import java.time.LocalDate;
import java.util.List;
import java.util.function.Consumer;
import java.util.stream.Collectors;

import studybuddy.application.EventBus;
import studybuddy.application.LearningContentService;
import studybuddy.application.MaterialService;
import studybuddy.application.StudyEvent;
import studybuddy.domain.AgentResponse;
import studybuddy.domain.Concept;
import studybuddy.domain.Course;
import studybuddy.domain.CreatedItem;
import studybuddy.domain.Material;
import studybuddy.domain.TraceStep;
import studybuddy.persistence.PreferenceRepository;

/**
 * The Study Coach's entry point (F12). For each message it gathers the context (courses, materials, concepts,
 * preferences), runs the {@link Planner} and remembers the conversation. Messages are handled one at a time.
 */
public class AgentController {

    public static final int MAX_MESSAGE_LENGTH = 2000;
    private static final int MAX_MEMORY_TURNS = 20;
    private static final int MAX_PREFERENCES = 20;

    private final Planner planner;
    private final ToolManager toolManager;
    private final ConversationMemory memory = new ConversationMemory(MAX_MEMORY_TURNS);
    private final MaterialService materialService;
    private final LearningContentService contentService;
    private final PreferenceRepository preferences;
    private final EventBus eventBus;
    /** Whether this conversation has been announced for the study history, which gets one line per conversation. */
    private boolean conversationAnnounced;

    public AgentController(Planner planner, ToolManager toolManager, MaterialService materialService,
                           LearningContentService contentService, PreferenceRepository preferences,
                           EventBus eventBus) {
        this.planner = planner;
        this.toolManager = toolManager;
        this.materialService = materialService;
        this.contentService = contentService;
        this.preferences = preferences;
        this.eventBus = eventBus;
    }

    /**
     * Handles one message of the conversation.
     *
     * @param courseId the selected course, or null; if there is only one course, it is selected
     * @param onStep   hears each step start and finish, on this thread
     * @throws IllegalArgumentException if the message is empty or too long
     */
    public synchronized AgentResponse handleMessage(Long courseId, String message, Consumer<TraceStep> onStep) {
        String goal = message == null ? "" : message.strip();
        if (goal.isEmpty()) {
            throw new IllegalArgumentException("Please enter a message.");
        }
        if (goal.length() > MAX_MESSAGE_LENGTH) {
            throw new IllegalArgumentException("Messages can be at most " + MAX_MESSAGE_LENGTH + " characters.");
        }
        List<Course> courses = materialService.listCourses();
        Course selected = selectedCourse(courseId, courses);
        AgentResponse response = planner.run(goal, buildContext(courses, selected), memory, toolManager,
                new AgentTrace(onStep));

        memory.addTurn(true, goal);
        memory.addTurn(false, withCreatedItems(response));
        if (response.status() != AgentResponse.Status.FAILED) {
            eventBus.publish(StudyEvent.of(StudyEvent.Type.COACH_RESPONDED, selected == null ? 0 : selected.getId(),
                    "Asked the Study Coach: " + goal));
            if (selected != null && !conversationAnnounced) {
                conversationAnnounced = true;
                eventBus.publish(StudyEvent.of(StudyEvent.Type.COACH_CONVERSATION_STARTED, selected.getId(), goal));
            }
        }
        return response;
    }

    /** Starts a new conversation. Stated preferences are kept. */
    public synchronized void resetConversation() {
        memory.clear();
        conversationAnnounced = false;
    }

    private static Course selectedCourse(Long courseId, List<Course> courses) {
        if (courseId != null) {
            return courses.stream().filter(c -> c.getId() == courseId).findFirst().orElse(null);
        }
        return courses.size() == 1 ? courses.get(0) : null;
    }

    private String buildContext(List<Course> courses, Course selected) {
        StringBuilder context = new StringBuilder("Today is ").append(LocalDate.now()).append(".\n");
        context.append("Courses: ").append(courses.isEmpty() ? "none yet; the student must create one in the app first"
                : courses.stream().map(c -> "#" + c.getId() + " " + c).collect(Collectors.joining("; "))).append('\n');
        if (selected == null) {
            context.append("No course is selected.\n");
        } else {
            context.append("Selected course: #").append(selected.getId()).append(' ').append(selected).append('\n');
            List<Material> materials = materialService.listMaterials(selected.getId());
            context.append("Its materials: ").append(materials.isEmpty()
                    ? "none yet; the student must upload some in the app first"
                    : materials.stream().map(m -> "#" + m.getId() + " " + m.getTitle() + " (" + m.getPageCount()
                            + " pages)").collect(Collectors.joining("; "))).append('\n');
            List<Concept> concepts = contentService.listConcepts(selected.getId());
            context.append("Its concepts: ").append(concepts.isEmpty()
                    ? "none extracted yet, so weak concepts cannot be tracked"
                    : concepts.stream().map(c -> "#" + c.getId() + " " + c.getName())
                            .collect(Collectors.joining("; "))).append('\n');
        }
        List<String> stated = preferences.findRecent(MAX_PREFERENCES);
        context.append("The student's stated preferences: ").append(stated.isEmpty() ? "none yet"
                : String.join("; ", stated)).append('\n');
        return context.toString().strip();
    }

    /** Remembers what was created, so that later messages can refer to it. */
    private static String withCreatedItems(AgentResponse response) {
        if (response.created().isEmpty()) {
            return response.text();
        }
        return response.text() + "\n(Created: " + response.created().stream().map(CreatedItem::label)
                .collect(Collectors.joining(", ")) + ")";
    }
}
