package studybuddy.presentation.gui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

import java.awt.Component;
import java.awt.Container;
import java.awt.GraphicsEnvironment;
import java.awt.event.MouseEvent;
import java.lang.reflect.InvocationTargetException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.BooleanSupplier;

import javax.swing.JButton;
import javax.swing.JTable;
import javax.swing.JTextField;
import javax.swing.ListSelectionModel;
import javax.swing.SwingUtilities;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import studybuddy.ai.FakeLLMClient;
import studybuddy.application.StudyAgentFacade;
import studybuddy.domain.Course;
import studybuddy.persistence.Database;

class MaterialsPanelTest {

    @TempDir
    Path dir;

    private Database db;
    private StudyAgentFacade facade;
    private Course course;
    private MaterialsPanel panel;

    @BeforeEach
    void setUp() throws Exception {
        assumeFalse(GraphicsEnvironment.isHeadless());
        db = Database.inMemory();
        facade = GuiTestFacade.create(db, new FakeLLMClient());
        course = facade.createCourse("EECS3311", "");
        facade.uploadMaterial(course.getId(), Files.writeString(dir.resolve("notes.txt"), "Facade notes"));
        SwingUtilities.invokeAndWait(() -> {
            panel = new MaterialsPanel(facade);
            panel.setCourse(course);
        });
        waitFor(() -> panel.rowCount() == 1);
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

    private static MouseEvent clicks(JTable table, int count) {
        return new MouseEvent(table, MouseEvent.MOUSE_PRESSED, 0, 0, 5, 5, count, false, MouseEvent.BUTTON1);
    }

    private String savedTitle() {
        return facade.listMaterials(course.getId()).get(0).getTitle();
    }

    @Test
    void doubleClickingATitleRenamesTheMaterial() throws Exception {
        JTable table = find(panel, JTable.class);
        boolean[] editing = new boolean[2];
        SwingUtilities.invokeAndWait(() -> {
            editing[0] = table.editCellAt(0, 0, clicks(table, 1));
            editing[1] = table.editCellAt(0, 0, clicks(table, 2));
            ((JTextField) table.getEditorComponent()).setText("Week 3 notes");
            table.getCellEditor().stopCellEditing();
        });

        assertFalse(editing[0], "one click only selects");
        assertTrue(editing[1], "two clicks edit");
        waitFor(() -> "Week 3 notes".equals(savedTitle()));
    }

    @Test
    void onlyTheTitleCanBeEdited() {
        JTable table = find(panel, JTable.class);

        assertTrue(table.isCellEditable(0, 0));
        for (int column = 1; column < table.getColumnCount(); column++) {
            assertFalse(table.isCellEditable(0, column), table.getColumnName(column));
        }
    }

    @Test
    void oneRowIsSelectedAtATime() {
        JTable table = find(panel, JTable.class);

        assertEquals(ListSelectionModel.SINGLE_SELECTION, table.getSelectionModel().getSelectionMode());
    }

    @Test
    void removeIsAButtonThatNeedsASelectedRow() throws Exception {
        JButton remove = null;
        for (Component child : ((Container) panel.getComponent(0)).getComponents()) {
            if (child instanceof JButton button && "Remove".equals(button.getText())) {
                remove = button;
            }
        }
        JTable table = find(panel, JTable.class);

        assertFalse(remove.isEnabled());
        SwingUtilities.invokeAndWait(() -> table.setRowSelectionInterval(0, 0));
        assertTrue(remove.isEnabled());
    }
}
