package studybuddy.presentation.gui;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

import java.awt.GraphicsEnvironment;
import java.lang.reflect.InvocationTargetException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.BooleanSupplier;

import javax.swing.SwingUtilities;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import studybuddy.ai.FakeLLMClient;
import studybuddy.application.StudyAgentFacade;
import studybuddy.domain.Course;
import studybuddy.domain.Material;
import studybuddy.persistence.Database;

class ConceptsPanelTest {

    @TempDir
    Path dir;

    private Database db;
    private StudyAgentFacade facade;
    private Course course;
    private ConceptsPanel panel;

    @BeforeEach
    void setUp() throws Exception {
        assumeFalse(GraphicsEnvironment.isHeadless());
        db = Database.inMemory();
        facade = GuiTestFacade.create(db, new FakeLLMClient());
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
}
