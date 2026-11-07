package studybuddy.presentation.gui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

import java.awt.Font;
import java.awt.GraphicsEnvironment;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.function.BooleanSupplier;

import javax.swing.JTextArea;
import javax.swing.SwingUtilities;
import javax.swing.table.DefaultTableModel;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import studybuddy.application.ApplicationFactory;
import studybuddy.application.StudyAgentFacade;
import studybuddy.config.AppConfig;
import studybuddy.domain.Course;

/** Builds the window without showing it and checks that service events refresh the panels (Observer). */
class PanelsSmokeTest {

    @TempDir
    Path dir;

    private boolean onEdt(BooleanSupplier check) throws InterruptedException, InvocationTargetException {
        boolean[] result = new boolean[1];
        SwingUtilities.invokeAndWait(() -> result[0] = check.getAsBoolean());
        return result[0];
    }

    /** Background refreshes finish later; poll until the condition holds. */
    private void waitFor(BooleanSupplier check) throws Exception {
        for (int i = 0; i < 100 && !onEdt(check); i++) {
            Thread.sleep(50);
        }
        assertTrue(onEdt(check), "condition not reached");
    }

    @Test
    void eventsRefreshThePanels() throws Exception {
        assumeFalse(GraphicsEnvironment.isHeadless());
        AppConfig config = AppConfig.load(dir.resolve("no.env"),
                Map.of("STUDYBUDDY_DB_PATH", dir.resolve("gui.db").toString()));
        try (StudyAgentFacade facade = ApplicationFactory.create(config)) {
            MainWindow[] window = new MainWindow[1];
            SwingUtilities.invokeAndWait(() -> window[0] = new MainWindow(facade, () -> { }));
            MaterialsPanel materials = (MaterialsPanel) window[0].panels().get(0);
            assertEquals("Materials", materials.title());
            assertNotEquals(Font.MONOSPACED, new JTextArea().getFont().getFamily());
            assertEquals(Theme.TABLE_ROW_HEIGHT, new StudyTable(new DefaultTableModel()).getRowHeight());
            assertFalse(onEdt(window[0]::courseSelectorShown), "no courses: shows text, not an empty dropdown");

            Course course = facade.createCourse("EECS3311", "");
            uploadSample(facade, course);

            waitFor(() -> materials.rowCount() == 1);
            assertTrue(onEdt(window[0]::courseSelectorShown));
        }
    }

    private void uploadSample(StudyAgentFacade facade, Course course) throws IOException {
        facade.uploadMaterial(course.getId(), Files.writeString(dir.resolve("notes.txt"), "Facade pattern notes"));
    }
}
