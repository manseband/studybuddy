package studybuddy.presentation.gui;

import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.function.Consumer;

import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.SwingWorker;

import studybuddy.application.StudyAgentFacade;
import studybuddy.domain.Course;

/**
 * Base class of every tab. A panel talks only to the facade, shows the course selected in the
 * main window, and runs slow work (database, AI) on a background thread so the window stays responsive.
 */
public abstract class FeaturePanel extends JPanel {

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

    protected void showError(String message) {
        JOptionPane.showMessageDialog(this, message, "StudyBuddy", JOptionPane.ERROR_MESSAGE);
    }

    protected void showInfo(String message) {
        JOptionPane.showMessageDialog(this, message, "StudyBuddy", JOptionPane.INFORMATION_MESSAGE);
    }
}
