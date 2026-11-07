package studybuddy.presentation.gui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

import java.awt.Component;
import java.awt.Container;
import java.awt.GraphicsEnvironment;
import java.lang.reflect.InvocationTargetException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;

import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JTable;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import studybuddy.ai.FakeLLMClient;
import studybuddy.application.StudyAgentFacade;
import studybuddy.domain.Concept;
import studybuddy.domain.Course;
import studybuddy.domain.Material;
import studybuddy.persistence.Database;

/** Drives the Concepts tab by clicking its real buttons, without showing a window. */
class ConceptsPanelTest {

    private static final String TWO_CANDIDATES = """
            {"concepts": [
              {"name": "Facade", "definition": "A simple front.", "pages": [1], "importance": 3, "prerequisites": []},
              {"name": "Observer", "definition": "Notifies listeners.", "pages": [1], "importance": 3, "prerequisites": []}]}""";

    @TempDir
    Path dir;

    private Database db;
    private FakeLLMClient llm;
    private StudyAgentFacade facade;
    private Course course;
    private ConceptsPanel panel;

    @BeforeEach
    void setUp() throws Exception {
        assumeFalse(GraphicsEnvironment.isHeadless());
        db = Database.inMemory();
        llm = new FakeLLMClient();
        facade = GuiTestFacade.create(db, llm);
        course = facade.createCourse("EECS3311", "");
        SwingUtilities.invokeAndWait(() -> panel = new ConceptsPanel(facade));
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

    private static void collectButtons(Container parent, List<JButton> buttons) {
        for (Component child : parent.getComponents()) {
            if (child instanceof JButton button) {
                buttons.add(button);
            } else if (child instanceof Container container) {
                collectButtons(container, buttons);
            }
        }
    }

    private List<JButton> buttons() {
        List<JButton> buttons = new ArrayList<>();
        collectButtons(panel, buttons);
        return buttons;
    }

    private JButton button(String text) {
        return buttons().stream().filter(b -> text.equals(b.getText())).findFirst().orElseThrow();
    }

    private static <T> T find(Container parent, Class<T> type) {
        for (Component child : parent.getComponents()) {
            if (type.isInstance(child)) {
                return type.cast(child);
            }
            if (child instanceof Container container) {
                T found = find(container, type);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    /** A course with one material and one saved concept, shown in the panel. */
    private void openCourseWithASavedConcept() throws Exception {
        Material material = facade.uploadMaterial(course.getId(), Files.writeString(dir.resolve("notes.txt"),
                "The Facade pattern gives one simple interface to a whole subsystem. ".repeat(20)));
        facade.saveConcepts(material.getId(), List.of(new Concept(0, course.getId(), "Existing", "Already saved.", 3)));
        SwingUtilities.invokeAndWait(() -> panel.setCourse(course));
        waitFor(() -> panel.rowCount() == 1 && button("Extract Concepts").isEnabled());
    }

    private void extractAndWaitForReview() throws Exception {
        llm.enqueueText(TWO_CANDIDATES);
        SwingUtilities.invokeAndWait(button("Extract Concepts")::doClick);
        waitFor(() -> panel.rowCount() == 2 && button("Save Concepts").isEnabled());
    }

    private List<String> savedNames() {
        return facade.listConcepts(course.getId()).stream().map(Concept::getName).sorted().toList();
    }

    @Test
    void showsTextInsteadOfAnEmptyDropdownWhenThereAreNoMaterials() throws Exception {
        assertFalse(onEdt(panel::materialSelectorShown));

        SwingUtilities.invokeAndWait(() -> panel.setCourse(course));
        Material material = facade.uploadMaterial(course.getId(), Files.writeString(dir.resolve("notes.txt"), "Notes"));
        SwingUtilities.invokeAndWait(panel::refresh);
        waitFor(panel::materialSelectorShown);

        facade.removeMaterial(material.getId());
        SwingUtilities.invokeAndWait(panel::refresh);
        waitFor(() -> !panel.materialSelectorShown());
    }

    @Test
    void buttonsRunInTheAgreedOrder() {
        assertEquals(List.of("Extract Concepts", "Remove", "Cancel", "Save Concepts", "Merge Into..."),
                buttons().stream().map(JButton::getText).filter(text -> !text.isEmpty()).toList());
    }

    @Test
    void extractingEmptiesTheTableAtOnceAndCannotBeRepeated() throws Exception {
        openCourseWithASavedConcept();
        llm.enqueueText(TWO_CANDIDATES);

        // checked in one step on the Swing thread, so the model's reply cannot arrive in between
        boolean[] whileExtracting = new boolean[3];
        SwingUtilities.invokeAndWait(() -> {
            button("Extract Concepts").doClick();
            whileExtracting[0] = panel.rowCount() == 0;
            whileExtracting[1] = button("Extract Concepts").isEnabled();
            whileExtracting[2] = find(panel, JComboBox.class).isEnabled();
        });

        assertTrue(whileExtracting[0], "the saved concepts leave the table immediately");
        assertFalse(whileExtracting[1]);
        assertFalse(whileExtracting[2]);
        waitFor(() -> panel.rowCount() == 2 && button("Save Concepts").isEnabled());
    }

    @Test
    void extractStaysGreyedOutDuringTheReview() throws Exception {
        openCourseWithASavedConcept();

        extractAndWaitForReview();

        assertFalse(button("Extract Concepts").isEnabled());
        assertTrue(button("Cancel").isEnabled());
        assertTrue(button("Save Concepts").isEnabled());
        assertFalse(button("Merge Into...").isEnabled());
    }

    @Test
    void cancelBringsBackTheSavedConceptsAndSavesNothing() throws Exception {
        openCourseWithASavedConcept();
        extractAndWaitForReview();

        SwingUtilities.invokeAndWait(button("Cancel")::doClick);

        waitFor(() -> panel.rowCount() == 1);
        assertEquals(List.of("Existing"), savedNames());
        assertTrue(button("Extract Concepts").isEnabled());
        assertFalse(button("Cancel").isEnabled());
        assertFalse(button("Save Concepts").isEnabled());
    }

    @Test
    void saveConceptsKeepsTheCandidatesIncludingACellStillBeingEdited() throws Exception {
        openCourseWithASavedConcept();
        extractAndWaitForReview();
        JTable table = find(panel, JTable.class);

        SwingUtilities.invokeAndWait(() -> {
            table.editCellAt(0, 0);
            ((JTextField) table.getEditorComponent()).setText("Facade pattern");
            button("Save Concepts").doClick();
        });

        waitFor(() -> panel.rowCount() == 3);
        assertEquals(List.of("Existing", "Facade pattern", "Observer"), savedNames());
        assertTrue(button("Extract Concepts").isEnabled());
    }
}
