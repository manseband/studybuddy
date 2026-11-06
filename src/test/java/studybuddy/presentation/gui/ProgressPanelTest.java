package studybuddy.presentation.gui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

import java.awt.GraphicsEnvironment;
import java.awt.image.BufferedImage;
import java.lang.reflect.InvocationTargetException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.function.BooleanSupplier;

import javax.swing.SwingUtilities;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import studybuddy.ai.FakeLLMClient;
import studybuddy.ai.LLMException;
import studybuddy.application.StudyAgentFacade;
import studybuddy.domain.Concept;
import studybuddy.domain.Course;
import studybuddy.domain.Difficulty;
import studybuddy.domain.Material;
import studybuddy.domain.QuestionType;
import studybuddy.domain.Quiz;
import studybuddy.domain.QuizAttempt;
import studybuddy.domain.QuizRequest;
import studybuddy.persistence.Database;

class ProgressPanelTest {

    @TempDir
    Path dir;

    private Database db;
    private FakeLLMClient llm;
    private StudyAgentFacade facade;
    private Course course;
    private ProgressPanel panel;

    @BeforeEach
    void setUp() throws Exception {
        assumeFalse(GraphicsEnvironment.isHeadless());
        db = Database.inMemory();
        llm = new FakeLLMClient();
        facade = GuiTestFacade.create(db, llm);
        course = facade.createCourse("EECS3311", "");
        Material material = facade.uploadMaterial(course.getId(), Files.writeString(dir.resolve("notes.txt"),
                "The Facade pattern gives one simple interface to a whole subsystem. ".repeat(20)));
        facade.saveConcepts(material.getId(), List.of(new Concept(0, course.getId(), "Facade", "Hides a subsystem.", 3)));
        SwingUtilities.invokeAndWait(() -> {
            panel = new ProgressPanel(facade);
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

    private void failAQuiz() {
        llm.enqueueText("""
                {"questions": [
                  {"type": "tf", "stem": "One", "answer": true, "excerpt": 1, "page": 1, "concept": "Facade"},
                  {"type": "tf", "stem": "Two", "answer": true, "excerpt": 1, "page": 1, "concept": "Facade"},
                  {"type": "tf", "stem": "Three", "answer": true, "excerpt": 1, "page": 1, "concept": "Facade"}]}""");
        Quiz quiz = facade.generateQuiz(new QuizRequest(course.getId(), List.of(), List.of(), 3,
                Set.of(QuestionType.TRUE_FALSE), Difficulty.EASY, false));
        QuizAttempt attempt = facade.startAttempt(quiz.getId());
        facade.submitQuiz(attempt);
    }

    private void refreshAndWaitForStatus(String expected) throws Exception {
        SwingUtilities.invokeAndWait(panel::refresh);
        waitFor(() -> panel.rowCount() == 1 && expected.equals(panel.statusAt(0)));
    }

    @Test
    void showsConceptsAndMarksWeakOnesAfterAQuiz() throws Exception {
        refreshAndWaitForStatus("Not enough data");

        failAQuiz();

        refreshAndWaitForStatus("Weak");
    }

    @Test
    void masteryBarPaintsEveryState() throws Exception {
        failAQuiz();
        refreshAndWaitForStatus("Weak");

        SwingUtilities.invokeAndWait(() -> {
            panel.setSize(900, 500);
            panel.doLayout();
            panel.validate();
            BufferedImage image = new BufferedImage(900, 500, BufferedImage.TYPE_INT_RGB);
            panel.paint(image.getGraphics());
        });
    }

    @Test
    void diagnoseShowsTheAnalysisOrTheFailure() throws Exception {
        failAQuiz();
        refreshAndWaitForStatus("Weak");
        llm.enqueueText("{\"patterns\": [\"You skip questions.\"], \"actions\": [\"Answer them all.\"]}");

        SwingUtilities.invokeAndWait(panel::runDiagnosis);
        waitFor(() -> panel.diagnosisText().contains("You skip questions."));

        llm.enqueueFailure(new LLMException("rate limited", true));
        SwingUtilities.invokeAndWait(panel::runDiagnosis);
        waitFor(() -> panel.diagnosisText().contains("unavailable"));
        assertEquals("Weak", panel.statusAt(0));
    }
}
