package studybuddy.presentation.gui;

import java.awt.BorderLayout;
import java.awt.event.MouseEvent;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.function.Consumer;

import javax.swing.BorderFactory;
import javax.swing.DefaultListModel;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JTextArea;
import javax.swing.ListSelectionModel;
import javax.swing.SwingUtilities;
import javax.swing.SwingWorker;

import studybuddy.application.StudyAgentFacade;
import studybuddy.domain.Course;

/**
 * Base class of every tab. A panel talks only to the facade, shows the course selected in the
 * main window, and runs slow work (database, AI) on a background thread so the window stays responsive.
 */
public abstract class FeaturePanel extends JPanel {

    /** Explains the lists made by {@link #multiSelectList}, in a dialog that chooses what to study. */
    protected static final String SCOPE_HINT = "Click to choose, Ctrl-click for several, click again to clear. "
            + "Leave both empty for the whole course.";

    protected final StudyAgentFacade facade;
    protected Course course;

    protected FeaturePanel(StudyAgentFacade facade) {
        this.facade = facade;
    }

    /** Text of this panel's tab. */
    public abstract String title();

    /** Reloads what the panel shows for the current course. Called on the Swing thread. */
    public abstract void refresh();

    /** Called by the main window when another course is selected; course may be null. */
    public void setCourse(Course course) {
        this.course = course;
        refresh();
    }

    protected void runInBackground(Runnable task) {
        runInBackground(() -> {
            task.run();
            return null;
        }, result -> { });
    }

    /** Runs work off the Swing thread, then onSuccess on it. Failures are shown in an error dialog. */
    protected <T> void runInBackground(Callable<T> work, Consumer<T> onSuccess) {
        runInBackground(work, onSuccess, error -> showError(error.getMessage()));
    }

    /** Runs work off the Swing thread, then onSuccess or onFailure on it. */
    protected <T> void runInBackground(Callable<T> work, Consumer<T> onSuccess, Consumer<Throwable> onFailure) {
        new SwingWorker<T, Void>() {
            @Override
            protected T doInBackground() throws Exception {
                return work.call();
            }

            @Override
            protected void done() {
                try {
                    onSuccess.accept(get());
                } catch (ExecutionException e) {
                    onFailure.accept(e.getCause());
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        }.execute();
    }

    /** A list where several items can be chosen, and clicking the only chosen item again clears the choice. */
    protected static <T> JList<T> multiSelectList(List<T> items) {
        DefaultListModel<T> listModel = new DefaultListModel<>();
        items.forEach(listModel::addElement);
        JList<T> list = new JList<>(listModel) {
            private boolean clickClears;

            @Override
            protected void processMouseEvent(MouseEvent e) {
                if (e.getID() == MouseEvent.MOUSE_PRESSED) {
                    clickClears = SwingUtilities.isLeftMouseButton(e) && !e.isControlDown() && !e.isShiftDown()
                            && getSelectedIndices().length == 1 && getSelectedIndex() == locationToIndex(e.getPoint());
                }
                super.processMouseEvent(e);
                if (e.getID() == MouseEvent.MOUSE_CLICKED && e.getClickCount() == 1 && clickClears) {
                    clearSelection();
                }
            }
        };
        list.setSelectionMode(ListSelectionModel.MULTIPLE_INTERVAL_SELECTION);
        return list;
    }

    /** The component with a label above it. */
    protected static JPanel titled(String title, JComponent content) {
        JPanel panel = new JPanel(new BorderLayout(0, 2));
        panel.add(new JLabel(title), BorderLayout.NORTH);
        panel.add(content, BorderLayout.CENTER);
        return panel;
    }

    /** Text that wraps to the available width and looks like a label. */
    protected static JTextArea wrappedText(String text) {
        JTextArea area = new JTextArea(text);
        area.setEditable(false);
        area.setLineWrap(true);
        area.setWrapStyleWord(true);
        area.setOpaque(false);
        area.setBorder(BorderFactory.createEmptyBorder(2, 0, 2, 0));
        return area;
    }

    protected void showError(String message) {
        JOptionPane.showMessageDialog(this, message, "StudyBuddy", JOptionPane.ERROR_MESSAGE);
    }

    protected void showInfo(String message) {
        JOptionPane.showMessageDialog(this, message, "StudyBuddy", JOptionPane.INFORMATION_MESSAGE);
    }
}
