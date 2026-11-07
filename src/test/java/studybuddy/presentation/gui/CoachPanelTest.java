package studybuddy.presentation.gui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

import java.awt.GraphicsEnvironment;
import java.lang.reflect.InvocationTargetException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;

import javax.swing.SwingUtilities;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import studybuddy.ai.FakeLLMClient;
import studybuddy.ai.LLMException;
import studybuddy.ai.LLMResponse;
import studybuddy.ai.ToolCall;
import studybuddy.application.StudyAgentFacade;
import studybuddy.domain.Course;
import studybuddy.domain.CreatedItem;
import studybuddy.persistence.Database;

/** Drives the Coach tab without showing a window. */
class CoachPanelTest {

    @TempDir
    Path dir;

    private Database db;
    private FakeLLMClient llm;
    private StudyAgentFacade facade;
    private Course course;
    private CoachPanel panel;
    private final List<CreatedItem> opened = new ArrayList<>();

    @BeforeEach
    void setUp() throws Exception {
        assumeFalse(GraphicsEnvironment.isHeadless());
        db = Database.inMemory();
        llm = new FakeLLMClient();
        facade = GuiTestFacade.create(db, llm);
        course = facade.createCourse("EECS3311", "");
        facade.uploadMaterial(course.getId(), Files.writeString(dir.resolve("notes.txt"),
                "The Facade pattern gives one simple interface to a whole subsystem. ".repeat(20)));
        SwingUtilities.invokeAndWait(() -> {
            panel = new CoachPanel(facade, opened::add);
            panel.setCourse(course);
        });
    }

    @AfterEach
    void tearDown() {
        if (db != null) {
            db.close();
        }
    }

    private boolean onEdt(BooleanSupplier check) throws InterruptedException, InvocationTargetException {
        boolean[] result = new boolean[1];
        SwingUtilities.invokeAndWait(() -> result[0] = check.getAsBoolean());
        return result[0];
    }

    private void waitFor(BooleanSupplier check) throws Exception {
        for (int i = 0; i < 100 && !onEdt(check); i++) {
            Thread.sleep(50);
        }
        assertTrue(onEdt(check), "condition not reached");
    }

    private void send(String message) throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            panel.typeMessage(message);
            panel.sendMessage();
        });
    }

    @Test
    void showsStepsAndALinkToWhatWasCreated() throws Exception {
        llm.enqueue(LLMResponse.toolCalls(new ToolCall("1", "generate_flashcards",
                Map.of("courseId", course.getId(), "count", 5))));
        llm.enqueueText("{\"cards\": [{\"front\": \"Front\", \"back\": \"Back\", \"excerpt\": 1, \"page\": 1, \"concept\": null}]}");
        llm.enqueueText("Your deck is ready.");

        send("Make cards");

        waitFor(() -> panel.transcriptText().contains("Coach: Your deck is ready."));
        assertTrue(panel.transcriptText().contains("You: Make cards"));
        assertEquals(1, panel.activityRows());
        assertTrue(panel.activityRow(0).contains("Created Flashcard deck"), panel.activityRow(0));
        assertEquals(1, panel.linkCount());

        SwingUtilities.invokeAndWait(() -> panel.clickLink(0));
        assertEquals(CreatedItem.Type.DECK, opened.get(0).type());
    }

    @Test
    void anUnavailableModelShowsTheErrorAndCanTryAgain() throws Exception {
        llm.enqueueFailure(new LLMException("rate limited", true));
        send("Help");
        waitFor(() -> panel.transcriptText().contains("rate limited"));

        llm.enqueueText("Back now.");
        send("Help again");
        waitFor(() -> panel.transcriptText().contains("Coach: Back now."));
    }

    @Test
    void newConversationClearsEverythingAndForgetsEarlierMessages() throws Exception {
        llm.enqueueText("Hello!");
        send("hi");
        waitFor(() -> panel.transcriptText().contains("Coach: Hello!"));

        SwingUtilities.invokeAndWait(panel::restartConversation);
        assertFalse(panel.transcriptText().contains("Hello!"));
        assertEquals(0, panel.activityRows());
        Thread.sleep(200);

        llm.enqueueText("Hi again.");
        send("hi");
        waitFor(() -> panel.transcriptText().contains("Coach: Hi again."));
        assertEquals(1, llm.lastRequest().messages().size());
    }

    @Test
    void blankMessagesAreNotSent() throws Exception {
        send("   ");

        Thread.sleep(100);
        assertEquals(0, llm.getRequests().size());
    }

    @Test
    void changingCourseStartsANewConversationButTheSameCourseDoesNot() throws Exception {
        llm.enqueueText("Hello!");
        send("hi");
        waitFor(() -> panel.transcriptText().contains("Coach: Hello!"));

        SwingUtilities.invokeAndWait(() -> panel.setCourse(course));
        assertTrue(panel.transcriptText().contains("Hello!"));

        Course other = facade.createCourse("MATH1000", "");
        SwingUtilities.invokeAndWait(() -> panel.setCourse(other));
        assertFalse(panel.transcriptText().contains("Hello!"));
    }
}
