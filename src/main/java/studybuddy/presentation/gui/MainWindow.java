package studybuddy.presentation.gui;

import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.awt.GridLayout;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JTabbedPane;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;
import javax.swing.SwingWorker;
import javax.swing.UIManager;

import studybuddy.application.ApplicationFactory;
import studybuddy.application.StudyAgentFacade;
import studybuddy.application.StudyEvent;
import studybuddy.application.StudyEventListener;
import studybuddy.config.AppConfig;
import studybuddy.domain.Course;

/**
 * The application window: a course selector above one tab per feature.
 * As an Observer of the EventBus it refreshes the panels whenever a service reports a change, so
 * services never call GUI code. Events arrive on background threads and are moved to the Swing thread.
 */
public class MainWindow implements StudyEventListener {

    private final StudyAgentFacade facade;
    private final List<FeaturePanel> panels = new ArrayList<>();
    private final JFrame frame = new JFrame("StudyBuddy");
    private final JComboBox<Course> courseSelector = new JComboBox<>();
    private final JTabbedPane tabs = new JTabbedPane();
    private final JButton deleteCourse = new JButton("Delete Course");
    /** Courses whose missing embeddings were already retried in this session (F01). */
    private final Set<Long> embeddingsChecked = new HashSet<>();
    private boolean reloadingCourses;

    /** @param onClosed run after the window is closed, e.g. to close the database */
    public MainWindow(StudyAgentFacade facade, Runnable onClosed) {
        this.facade = facade;
        Theme.install();
        panels.add(new MaterialsPanel(facade));
        panels.add(new ConceptsPanel(facade));
        panels.add(new AskPanel(facade));
        panels.add(new QuizPanel(facade));
        panels.add(new ProgressPanel(facade));
        panels.forEach(panel -> tabs.addTab(panel.title(), panel));

        frame.setLayout(new BorderLayout());
        frame.add(buildTopBar(), BorderLayout.NORTH);
        frame.add(tabs, BorderLayout.CENTER);
        frame.setSize(1000, 680);
        frame.setLocationRelativeTo(null);
        frame.setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE);
        frame.addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosed(WindowEvent e) {
                onClosed.run();
            }
        });

        courseSelector.addActionListener(e -> {
            if (!reloadingCourses) {
                courseSelected();
            }
        });
        facade.addListener(this);
        reloadCourses(null);
    }

    /** Runs the GUI: for {@code java -cp target/studybuddy.jar studybuddy.presentation.gui.MainWindow}. */
    public static void main(String[] args) {
        StudyAgentFacade facade = ApplicationFactory.create(AppConfig.load());
        launch(facade, facade::close);
    }

    /** Opens the window on the Swing thread. */
    public static void launch(StudyAgentFacade facade, Runnable onClosed) {
        SwingUtilities.invokeLater(() -> {
            try {
                UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
            } catch (ReflectiveOperationException | javax.swing.UnsupportedLookAndFeelException e) {
                // the default look and feel still works
            }
            new MainWindow(facade, onClosed).show();
        });
    }

    public void show() {
        frame.setVisible(true);
    }

    @Override
    public void onStudyEvent(StudyEvent event) {
        SwingUtilities.invokeLater(() -> {
            if (event.type() == StudyEvent.Type.COURSE_CHANGED) {
                reloadCourses(event.courseId());
            } else {
                panels.forEach(FeaturePanel::refresh);
            }
        });
    }

    /** The panels, in tab order. */
    List<FeaturePanel> panels() {
        return panels;
    }

    private JPanel buildTopBar() {
        JPanel bar = new JPanel(new BorderLayout());
        bar.setBorder(BorderFactory.createEmptyBorder(6, 8, 6, 8));

        JPanel left = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        left.add(new JLabel("Course:"));
        left.add(courseSelector);
        JButton newCourse = new JButton("New Course");
        newCourse.addActionListener(e -> newCourse());
        left.add(newCourse);
        deleteCourse.addActionListener(e -> deleteSelectedCourse());
        left.add(deleteCourse);
        bar.add(left, BorderLayout.WEST);

        bar.add(buildAiStatus(), BorderLayout.EAST);
        return bar;
    }

    private JPanel buildAiStatus() {
        boolean connected = facade.isAiAvailable();
        JLabel state = new JLabel(connected ? "connected" : "no API key (keyword search only; see .env.example)");
        state.setForeground(connected ? Theme.AI_CONNECTED : Theme.AI_NOT_CONNECTED);
        JPanel status = new JPanel(new BorderLayout());
        status.add(new JLabel("AI: "), BorderLayout.WEST);
        status.add(state, BorderLayout.CENTER);
        return status;
    }

    private void reloadCourses(Long preferredCourseId) {
        Course previous = (Course) courseSelector.getSelectedItem();
        long keep = preferredCourseId != null ? preferredCourseId : previous == null ? 0 : previous.getId();
        reloadingCourses = true;
        courseSelector.removeAllItems();
        Course toSelect = null;
        for (Course c : facade.listCourses()) {
            courseSelector.addItem(c);
            if (c.getId() == keep) {
                toSelect = c;
            }
        }
        if (toSelect != null) {
            courseSelector.setSelectedItem(toSelect);
        }
        reloadingCourses = false;
        courseSelected();
    }

    private void courseSelected() {
        Course selected = (Course) courseSelector.getSelectedItem();
        deleteCourse.setEnabled(selected != null);
        panels.forEach(panel -> panel.setCourse(selected));
        if (selected != null && facade.isAiAvailable() && embeddingsChecked.add(selected.getId())) {
            retryMissingEmbeddings(selected.getId());
        }
    }

    /** F01: embeddings that failed earlier are requested again when the course is opened. */
    private void retryMissingEmbeddings(long courseId) {
        new SwingWorker<Void, Void>() {
            @Override
            protected Void doInBackground() {
                facade.refreshEmbeddings(courseId);
                return null;
            }
        }.execute();
    }

    private void newCourse() {
        JTextField name = new JTextField();
        JTextField code = new JTextField();
        JTextField exam = new JTextField();
        JPanel form = new JPanel(new GridLayout(0, 1, 0, 2));
        form.add(new JLabel("Course name (required):"));
        form.add(name);
        form.add(new JLabel("Course code:"));
        form.add(code);
        form.add(new JLabel("Exam date, yyyy-mm-dd (optional):"));
        form.add(exam);
        int choice = JOptionPane.showConfirmDialog(frame, form, "New Course", JOptionPane.OK_CANCEL_OPTION,
                JOptionPane.PLAIN_MESSAGE);
        if (choice != JOptionPane.OK_OPTION) {
            return;
        }
        try {
            LocalDate examDate = exam.getText().isBlank() ? null : LocalDate.parse(exam.getText().strip());
            facade.createCourse(name.getText(), code.getText(), examDate);
        } catch (DateTimeParseException e) {
            JOptionPane.showMessageDialog(frame, "The exam date must look like 2026-12-10.");
        } catch (IllegalArgumentException e) {
            JOptionPane.showMessageDialog(frame, e.getMessage());
        }
    }

    private void deleteSelectedCourse() {
        Course selected = (Course) courseSelector.getSelectedItem();
        if (selected == null) {
            return;
        }
        int materials = facade.listMaterials(selected.getId()).size();
        int choice = JOptionPane.showConfirmDialog(frame, "Permanently delete " + selected.getName() + " with "
                + materials + " material(s) and all its concepts, quizzes, flashcards, plans and history?",
                "Delete Course", JOptionPane.OK_CANCEL_OPTION, JOptionPane.WARNING_MESSAGE);
        if (choice == JOptionPane.OK_OPTION) {
            facade.deleteCourse(selected.getId());
        }
    }
}
