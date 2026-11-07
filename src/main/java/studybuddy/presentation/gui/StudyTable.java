package studybuddy.presentation.gui;

import java.awt.BasicStroke;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.event.MouseEvent;

import javax.swing.JTable;
import javax.swing.ListSelectionModel;
import javax.swing.SwingUtilities;
import javax.swing.table.TableModel;

/**
 * A table where one row is selected at a time, since every action works on a single row. Clicking the
 * selected row again, or the empty space below the rows, clears the selection. Its grid lines are always
 * one screen pixel thick. JTable's own lines are one unit thick, and at display scales such as 120% a unit
 * is 1.2 screen pixels, which rounds to 1 or 2 depending on the row.
 */
class StudyTable extends JTable {

    /** A stroke width of 0 means the thinnest line the screen can draw, whatever the scale. */
    private static final BasicStroke ONE_SCREEN_PIXEL = new BasicStroke(0);

    /** Whether the mouse was pressed on the row that was already selected. */
    private boolean pressedOnSelectedRow;

    StudyTable(TableModel model) {
        super(model);
        setRowHeight(Theme.TABLE_ROW_HEIGHT);
        setShowGrid(false);
        setIntercellSpacing(new Dimension(0, 0));
        setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        setFillsViewportHeight(true);
    }

    /**
     * The selection is cleared on the click, not the press, so that a double-click on the selected row still
     * edits it: the first click clears the row and the second selects it again and starts editing.
     */
    @Override
    protected void processMouseEvent(MouseEvent e) {
        boolean plainClick = SwingUtilities.isLeftMouseButton(e) && !e.isControlDown() && !e.isShiftDown();
        if (e.getID() == MouseEvent.MOUSE_PRESSED) {
            int row = rowAtPoint(e.getPoint());
            pressedOnSelectedRow = plainClick && row >= 0 && isRowSelected(row);
            if (plainClick && row < 0) {
                clearSelection();
            }
        }
        super.processMouseEvent(e);
        if (e.getID() == MouseEvent.MOUSE_CLICKED && e.getClickCount() == 1 && pressedOnSelectedRow) {
            clearSelection();
        }
    }

    @Override
    protected void paintComponent(Graphics g) {
        super.paintComponent(g);
        Graphics2D lines = (Graphics2D) g.create();
        lines.setColor(getGridColor());
        lines.setStroke(ONE_SCREEN_PIXEL);
        int y = 0;
        for (int row = 0; row < getRowCount(); row++) {
            y += getRowHeight(row);
            lines.drawLine(0, y - 1, getWidth(), y - 1);
        }
        int x = 0;
        for (int column = 0; column < getColumnCount(); column++) {
            x += getColumnModel().getColumn(column).getWidth();
            lines.drawLine(x - 1, 0, x - 1, y - 1);
        }
        lines.dispose();
    }
}
