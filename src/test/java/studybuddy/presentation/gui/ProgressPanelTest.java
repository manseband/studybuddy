package studybuddy.presentation.gui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

import java.awt.GraphicsEnvironment;
import java.awt.image.BufferedImage;
import java.lang.reflect.InvocationTargetException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BooleanSupplier;

import javax.swing.SwingUtilities;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import studybuddy.ai.FakeLLMClient;
import studybuddy.ai.LLMException;
import studybuddy.application.StudyAgentFacade;
import studybuddy.domain.ActivityType;
import studybuddy.domain.Concept;
import studybuddy.domain.Course;
import studybuddy.domain.Difficulty;
import studybuddy.domain.Material;
import studybuddy.domain.QuestionType;
import studybuddy.domain.Quiz;
import studybuddy.domain.QuizAttempt;
import studybuddy.domain.QuizRequest;
import studybuddy.domain.ReportFormat;
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
                Set.of(QuestionType.TRUE_FALSE), Difficulty.EASY));
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
    void showsTheAverageScoreAndStreak() throws Exception {
        waitFor(() -> panel.summaryText().contains("No graded quizzes yet"));
        assertTrue(panel.summaryText().contains("Current streak: 0 days"), panel.summaryText());

        failAQuiz();
        SwingUtilities.invokeAndWait(panel::refresh);

        waitFor(() -> panel.summaryText().contains("Average score: 0% over 1 quiz"));
        assertTrue(panel.summaryText().contains("Current streak: 1 day"), panel.summaryText());
    }

    @Test
    void listsTheHistoryAndFiltersIt() throws Exception {
        waitFor(() -> panel.historyRowCount() == 1);
        assertEquals("Material uploaded", panel.activityAt(0));

        failAQuiz();
        SwingUtilities.invokeAndWait(panel::refresh);
        waitFor(() -> panel.historyRowCount() == 3);
        assertEquals("Material uploaded", panel.activityAt(2));

        SwingUtilities.invokeAndWait(() -> {
            panel.setHistoryFilter("", "", ActivityType.MATERIAL);
            panel.loadHistory();
        });
        waitFor(() -> panel.historyRowCount() == 1);

        // a refresh keeps the filter
        SwingUtilities.invokeAndWait(panel::refresh);
        Thread.sleep(200);
        waitFor(() -> panel.historyRowCount() == 1);

        SwingUtilities.invokeAndWait(() -> {
            panel.setHistoryFilter(LocalDate.now().plusDays(1).toString(), "", null);
            panel.loadHistory();
        });
        waitFor(() -> panel.historyRowCount() == 0 && panel.historyMessage().contains("No activity matches"));
    }

    @Test
    void aBadDateRangeRunsNoQuery() throws Exception {
        waitFor(() -> panel.historyRowCount() == 1);

        SwingUtilities.invokeAndWait(() -> {
            panel.setHistoryFilter("yesterday", "", null);
            panel.loadHistory();
        });
        assertEquals("Enter dates as YYYY-MM-DD.", panel.historyMessage());

        SwingUtilities.invokeAndWait(() -> {
            panel.setHistoryFilter("2026-11-05", "2026-11-01", null);
            panel.loadHistory();
        });
        waitFor(() -> panel.historyMessage().contains("start date"));
        assertEquals(1, panel.historyRowCount());
    }

    @Test
    void aCourseWithoutActivitySuggestsWhatToDo() throws Exception {
        Course empty = facade.createCourse("Empty", "");

        SwingUtilities.invokeAndWait(() -> panel.setCourse(empty));

        waitFor(() -> panel.historyMessage().contains("No activity yet"));
        assertEquals(0, panel.historyRowCount());
    }

    @Test
    void exportsTheReportOrReportsTheFailure() throws Exception {
        Path file = dir.resolve("report.csv");
        List<String> failures = new CopyOnWriteArrayList<>();

        SwingUtilities.invokeAndWait(() -> panel.export(file, ReportFormat.CSV, failures::add));
        waitFor(() -> panel.exportMessage().equals("Saved report.csv"));
        assertTrue(Files.readString(file).contains("Material uploaded"));
        assertTrue(failures.isEmpty());

        SwingUtilities.invokeAndWait(() -> panel.export(dir.resolve("missing").resolve("report.csv"), ReportFormat.CSV,
                failures::add));
        waitFor(() -> !failures.isEmpty());
        assertTrue(failures.get(0).contains("Choose another location"), failures.get(0));
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
