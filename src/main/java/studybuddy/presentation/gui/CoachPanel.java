package studybuddy.presentation.gui;

import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.FlowLayout;

import javax.swing.BorderFactory;
import javax.swing.DefaultListModel;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;

import studybuddy.application.StudyAgentFacade;
import studybuddy.domain.AgentResponse;
import studybuddy.domain.Course;
import studybuddy.domain.TraceStep;

/**
 * F09: a chat with the Study Coach for the selected course, with an activity list showing each step as it
 * happens. The coach runs in the background.
 */
public class CoachPanel extends FeaturePanel {

    private static final int ACTIVITY_WIDTH = 300;

    private final JTextArea transcript = new JTextArea();
    private final JTextField input = new JTextField();
    private final JButton send = new JButton("Send");
    private final JButton newConversation = new JButton("New Conversation");
    private final JLabel status = new JLabel(" ");
    private final DefaultListModel<String> activity = new DefaultListModel<>();
    /** Rows of the activity list before the current message's steps. */
    private int activityBase;
    /** Changes when the conversation restarts, so that a late reply to the old one is ignored. */
    private int conversation;
    private boolean busy;

    public CoachPanel(StudyAgentFacade facade) {
        super(facade);
        setLayout(new BorderLayout(0, 6));
        setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));

        transcript.setEditable(false);
        transcript.setLineWrap(true);
        transcript.setWrapStyleWord(true);
        input.setFont(Theme.textFont());
        input.addActionListener(e -> sendMessage());
        send.addActionListener(e -> sendMessage());
        newConversation.addActionListener(e -> restartConversation());

        JPanel controls = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        controls.add(send);
        controls.add(newConversation);
        controls.add(status);
        JPanel entry = new JPanel(new BorderLayout(0, 4));
        entry.add(input, BorderLayout.CENTER);
        entry.add(controls, BorderLayout.SOUTH);
        JPanel chat = new JPanel(new BorderLayout(0, 6));
        chat.add(new JScrollPane(transcript), BorderLayout.CENTER);
        chat.add(entry, BorderLayout.SOUTH);

        JList<String> activityList = new JList<>(activity);
        JPanel side = titled("Agent activity", new JScrollPane(activityList));
        JSplitPane split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, chat, side);
        split.setResizeWeight(1);
        side.setPreferredSize(new Dimension(ACTIVITY_WIDTH, 0));
        add(split, BorderLayout.CENTER);
        showWelcome();
        updateControls();
    }

    @Override
    public String title() {
        return "Coach";
    }

    /** A conversation belongs to one course. */
    @Override
    public void setCourse(Course newCourse) {
        boolean sameCourse = course != null && newCourse != null && course.getId() == newCourse.getId();
        super.setCourse(newCourse);
        if (!sameCourse) {
            restartConversation();
        }
    }

    @Override
    public void refresh() {
        updateControls();
    }

    private void updateControls() {
        send.setEnabled(course != null && !busy);
        input.setEnabled(course != null);
    }

    private void showWelcome() {
        transcript.setText("Tell the coach a study goal, such as \"Quiz me on what I keep getting wrong\".\n");
    }

    /** Runs when New Conversation is clicked or the course changes. */
    void restartConversation() {
        conversation++;
        busy = false;
        status.setText(" ");
        showWelcome();
        activity.clear();
        updateControls();
        runInBackground(facade::resetCoach);
    }

    /** Runs when Send is clicked or Enter is pressed. */
    void sendMessage() {
        String message = input.getText().strip();
        if (course == null || busy || message.isEmpty()) {
            return;
        }
        long courseId = course.getId();
        int thisConversation = conversation;
        input.setText("");
        transcript.append("\nYou: " + message + "\n");
        activityBase = activity.size();
        busy = true;
        status.setText("Working...");
        updateControls();
        runInBackground(() -> facade.askCoach(courseId, message,
                step -> SwingUtilities.invokeLater(() -> showStep(step, thisConversation))), response -> {
            if (thisConversation == conversation) {
                showResponse(response);
            }
        }, error -> {
            if (thisConversation == conversation) {
                finish();
                transcript.append("\nCoach: " + error.getMessage() + "\n");
            }
        });
    }

    private void showStep(TraceStep step, int stepConversation) {
        if (stepConversation != conversation) {
            return;
        }
        int row = activityBase + step.number() - 1;
        String text = step.number() + ". " + switch (step.status()) {
            case RUNNING -> step.description() + "...";
            case SUCCEEDED -> step.result();
            case FAILED -> "Failed: " + step.result();
        };
        if (row < activity.size()) {
            activity.set(row, text);
        } else {
            activity.addElement(text);
        }
    }

    private void showResponse(AgentResponse response) {
        finish();
        transcript.append("\nCoach: " + response.text() + "\n");
        transcript.setCaretPosition(transcript.getDocument().getLength());
    }

    private void finish() {
        busy = false;
        status.setText(" ");
        updateControls();
    }

    /** Puts text in the message box as if it had been typed; used by tests. */
    void typeMessage(String text) {
        input.setText(text);
    }

    /** The conversation so far; used by tests. */
    String transcriptText() {
        return transcript.getText();
    }

    /** The rows of the activity list; used by tests. */
    int activityRows() {
        return activity.size();
    }

    /** A row of the activity list; used by tests. */
    String activityRow(int row) {
        return activity.get(row);
    }
}
