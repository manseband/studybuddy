package studybuddy.presentation.gui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

import java.awt.Component;
import java.awt.GraphicsEnvironment;
import java.awt.Point;
import java.awt.event.InputEvent;
import java.awt.event.MouseEvent;
import java.util.List;

import javax.swing.JList;
import javax.swing.SwingUtilities;
import javax.swing.table.DefaultTableModel;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Sends real mouse events to tables and lists: a repeated click or a click on empty space clears the choice. */
class DeselectTest {

    private StudyTable table;
    private JList<String> list;

    @BeforeEach
    void setUp() throws Exception {
        assumeFalse(GraphicsEnvironment.isHeadless());
        SwingUtilities.invokeAndWait(() -> {
            table = new StudyTable(new DefaultTableModel(new Object[][] {{"A"}, {"B"}}, new Object[] {"Name"}));
            table.setSize(300, 300);
            table.getColumnModel().getColumn(0).setWidth(300);
            list = FeaturePanel.multiSelectList(List.of("A", "B", "C"));
            list.setSize(300, 300);
        });
    }

    private static void click(Component target, Point at, int clickCount, int modifiers) throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            for (int id : new int[] {MouseEvent.MOUSE_PRESSED, MouseEvent.MOUSE_RELEASED, MouseEvent.MOUSE_CLICKED}) {
                target.dispatchEvent(new MouseEvent(target, id, System.currentTimeMillis(),
                        modifiers | InputEvent.BUTTON1_DOWN_MASK, at.x, at.y, clickCount, false, MouseEvent.BUTTON1));
            }
        });
    }

    private Point rowPoint(int row) {
        return new Point(10, table.getCellRect(row, 0, true).y + 2);
    }

    private Point itemPoint(int index) {
        return list.indexToLocation(index);
    }

    @Test
    void clickingTheSelectedRowAgainClearsIt() throws Exception {
        click(table, rowPoint(0), 1, 0);
        assertEquals(0, table.getSelectedRow());

        click(table, rowPoint(0), 1, 0);
        assertEquals(-1, table.getSelectedRow());

        click(table, rowPoint(0), 1, 0);
        assertEquals(0, table.getSelectedRow());
    }

    @Test
    void clickingAnotherRowMovesTheSelection() throws Exception {
        click(table, rowPoint(0), 1, 0);
        click(table, rowPoint(1), 1, 0);

        assertEquals(1, table.getSelectedRow());
    }

    @Test
    void clickingBelowTheRowsClearsTheSelection() throws Exception {
        click(table, rowPoint(1), 1, 0);

        click(table, new Point(10, 250), 1, 0);

        assertEquals(-1, table.getSelectedRow());
    }

    @Test
    void doubleClickingTheSelectedRowStillEdits() throws Exception {
        click(table, rowPoint(0), 1, 0);

        click(table, rowPoint(0), 1, 0);
        click(table, rowPoint(0), 2, 0);

        assertTrue(table.isEditing());
        assertEquals(0, table.getSelectedRow());
    }

    @Test
    void clickingTheOnlyChosenListItemAgainClearsIt() throws Exception {
        click(list, itemPoint(1), 1, 0);
        assertEquals(1, list.getSelectedIndex());

        click(list, itemPoint(1), 1, 0);
        assertTrue(list.isSelectionEmpty());
    }

    @Test
    void ctrlClickStillChoosesSeveralAndAPlainClickKeepsOne() throws Exception {
        click(list, itemPoint(0), 1, 0);
        click(list, itemPoint(2), 1, InputEvent.CTRL_DOWN_MASK);
        assertEquals(2, list.getSelectedIndices().length);

        click(list, itemPoint(2), 1, 0);
        assertEquals(List.of("C"), list.getSelectedValuesList());
        assertFalse(list.isSelectionEmpty());

        click(list, itemPoint(2), 1, 0);
        assertTrue(list.isSelectionEmpty());
    }
}
