package studybuddy.presentation.gui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

import java.awt.Component;
import java.awt.Container;
import java.awt.GraphicsEnvironment;
import java.lang.reflect.InvocationTargetException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.function.BooleanSupplier;

import javax.swing.JButton;
import javax.swing.JRadioButton;
import javax.swing.JTextArea;
import javax.swing.SwingUtilities;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import studybuddy.ai.FakeLLMClient;
import studybuddy.ai.LLMException;
import studybuddy.application.StudyAgentFacade;
import studybuddy.domain.Course;
import studybuddy.domain.Difficulty;
import studybuddy.domain.QuestionType;
import studybuddy.domain.Quiz;
import studybuddy.domain.QuizAttempt;
import studybuddy.domain.QuizRequest;
import studybuddy.persistence.Database;

/** Drives the Quizzes tab without showing a window; the panel must not open a dialog here. */
class QuizPanelTest {

    @TempDir
    Path dir;

    private Database db;

    @AfterEach
    void tearDown() {
        if (db != null) {
            db.close();
        }
    }

    private StudyAgentFacade facade(FakeLLMClient llm) {
        db = Database.inMemory();
        return GuiTestFacade.create(db, llm);
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

    @Test
    void listsAQuizThenTakesItAndShowsResults() throws Exception {
        assumeFalse(GraphicsEnvironment.isHeadless());
        FakeLLMClient llm = new FakeLLMClient();
        StudyAgentFacade facade = facade(llm);
        Course course = facade.createCourse("EECS3311", "");
        facade.uploadMaterial(course.getId(), Files.writeString(dir.resolve("notes.txt"),
                "The Facade pattern gives one simple interface to a whole subsystem. ".repeat(5)));
        llm.enqueueText("""
                {"questions": [
                  {"type": "mcq", "stem": "Which hides a subsystem?", "options": ["Facade", "Observer", "Builder", "Command"],
                   "correctIndex": 0, "excerpt": 1, "page": 1, "concept": null},
                  {"type": "tf", "stem": "An Adapter converts an interface.", "answer": true, "excerpt": 1, "page": 1, "concept": null},
                  {"type": "short", "stem": "What does a Facade offer?", "referenceAnswer": "One simple interface.",
                   "rubric": "Mentions a simple interface.", "excerpt": 1, "page": 1, "concept": null}]}""");
        Quiz quiz = facade.generateQuiz(new QuizRequest(course.getId(), List.of(), List.of(), 3,
                Set.of(QuestionType.MULTIPLE_CHOICE, QuestionType.TRUE_FALSE, QuestionType.SHORT_ANSWER),
                Difficulty.EASY));

        QuizPanel[] panel = new QuizPanel[1];
        SwingUtilities.invokeAndWait(() -> {
            panel[0] = new QuizPanel(facade);
            panel[0].setCourse(course);
        });
        waitFor(() -> panel[0].rowCount() == 1);

        SwingUtilities.invokeAndWait(() -> panel[0].openQuiz(quiz));
        assertEquals(QuizPanel.TAKE_VIEW, panel[0].currentView());

        // click the right option and a wrong true/false; the blank short answer is graded without a model call
        SwingUtilities.invokeAndWait(() -> {
            clickRadio(panel[0], "A) Facade");
            clickRadio(panel[0], "False");
            panel[0].submitAnswers();
        });
        waitFor(() -> QuizPanel.RESULTS_VIEW.equals(panel[0].currentView()));

        QuizAttempt attempt = facade.listAttempts(course.getId()).get(0);
        assertEquals("A", attempt.answerFor(quiz.getQuestions().get(0).getId()));
        assertEquals("false", attempt.answerFor(quiz.getQuestions().get(1).getId()));
        assertEquals(1.0, attempt.getTotalScore());
        assertEquals(1, llm.getRequests().size());

        // Explain shows the explanation in the first question's card and disables that button
        llm.enqueueText("{\"explanation\": \"A Facade hides a subsystem.\", \"misconception\": null, \"excerpt\": 1}");
        SwingUtilities.invokeAndWait(() -> findButton(panel[0], "Explain").doClick());
        waitFor(() -> allText(panel[0]).contains("A Facade hides a subsystem."));
        assertTrue(allText(panel[0]).contains("Read source: notes.txt, p. 1"), allText(panel[0]));

        // a failure shows a message and the button can be used again
        llm.enqueueFailure(new LLMException("rate limited", true));
        JButton[] next = new JButton[1];
        SwingUtilities.invokeAndWait(() -> {
            next[0] = findButton(panel[0], "Explain");
            next[0].doClick();
        });
        waitFor(() -> allText(panel[0]).contains("The explanation is unavailable"));
        assertTrue(allText(panel[0]).contains("rate limited"), allText(panel[0]));
        assertTrue(next[0].isEnabled());
    }

    /** The first enabled button with this text. */
    private static JButton findButton(Container parent, String text) {
        for (Component child : parent.getComponents()) {
            if (child instanceof JButton button && text.equals(button.getText()) && button.isEnabled()) {
                return button;
            }
            if (child instanceof Container container) {
                JButton found = findButton(container, text);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    private static String allText(Container parent) {
        StringBuilder text = new StringBuilder();
        for (Component child : parent.getComponents()) {
            if (child instanceof JTextArea area) {
                text.append(area.getText()).append('\n');
            } else if (child instanceof JButton button) {
                text.append(button.getText()).append('\n');
            } else if (child instanceof Container container) {
                text.append(allText(container));
            }
        }
        return text.toString();
    }

    private static void clickRadio(Container parent, String label) {
        JRadioButton button = findRadio(parent, label);
        assertNotNull(button, "no radio button " + label);
        button.doClick();
    }

    private static JRadioButton findRadio(Container parent, String label) {
        for (Component child : parent.getComponents()) {
            if (child instanceof JRadioButton radio && label.equals(radio.getText())) {
                return radio;
            }
            if (child instanceof Container container) {
                JRadioButton found = findRadio(container, label);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }
}
