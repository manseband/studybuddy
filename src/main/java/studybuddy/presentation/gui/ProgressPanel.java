package studybuddy.presentation.gui;

import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.FlowLayout;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.util.List;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JTable;
import javax.swing.JTextArea;
import javax.swing.table.DefaultTableModel;
import javax.swing.table.TableCellRenderer;

import studybuddy.application.StudyAgentFacade;
import studybuddy.domain.ConceptMastery;
import studybuddy.domain.Course;

/**
 * F09: concepts ranked by mastery, weakest first, with an optional AI diagnosis of the mistakes.
 * Scores update on their own: any quiz result triggers an event, which refreshes this panel.
 */
public class ProgressPanel extends FeaturePanel {

    private static final int MASTERY_COLUMN = 1;

    private final DefaultTableModel model = new DefaultTableModel(
            new Object[] {"Concept", "Mastery", "Evidence", "Trend", "Status"}, 0) {
        @Override
        public boolean isCellEditable(int row, int column) {
            return false;
        }
    };
    private final JTable table = new StudyTable(model);
    private final JButton diagnose = new JButton("Diagnose");
    private final JLabel status = new JLabel(" ");
    private final JTextArea diagnosis = new JTextArea(7, 40);

    public ProgressPanel(StudyAgentFacade facade) {
        super(facade);
        setLayout(new BorderLayout(0, 6));
        setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));

        JPanel top = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        diagnose.addActionListener(e -> runDiagnosis());
        top.add(diagnose);
        top.add(status);
        add(top, BorderLayout.NORTH);

        table.getColumnModel().getColumn(MASTERY_COLUMN).setCellRenderer(new MasteryBar());
        diagnosis.setEditable(false);
        diagnosis.setLineWrap(true);
        diagnosis.setWrapStyleWord(true);
        JSplitPane split = new JSplitPane(JSplitPane.VERTICAL_SPLIT, new JScrollPane(table), new JScrollPane(diagnosis));
        split.setResizeWeight(0.7);
        add(split, BorderLayout.CENTER);
        diagnose.setEnabled(false);
    }

    @Override
    public String title() {
        return "Progress";
    }

    /** The diagnosis belongs to one course. */
    @Override
    public void setCourse(Course newCourse) {
        diagnosis.setText("");
        status.setText(" ");
        super.setCourse(newCourse);
    }

    @Override
    public void refresh() {
        if (course == null) {
            show(List.of());
            return;
        }
        long courseId = course.getId();
        runInBackground(() -> facade.getMastery(courseId), this::show);
    }

    /** Rows currently shown; used by tests. */
    int rowCount() {
        return model.getRowCount();
    }

    /** The status column of a row; used by tests. */
    String statusAt(int row) {
        return (String) model.getValueAt(row, 4);
    }

    /** The diagnosis text; used by tests. */
    String diagnosisText() {
        return diagnosis.getText();
    }

    private void show(List<ConceptMastery> mastery) {
        model.setRowCount(0);
        for (ConceptMastery m : mastery) {
            model.addRow(new Object[] {m.conceptName(), m, m.evidenceCount() + " answer(s)", m.trend().arrow(),
                    m.statusLabel()});
        }
        diagnose.setEnabled(mastery.stream().anyMatch(ConceptMastery::weak));
        if (mastery.isEmpty()) {
            status.setText(course == null ? " " : "No concepts yet. Extract them in the Concepts tab first.");
        } else if (mastery.stream().allMatch(m -> m.evidenceCount() == 0)) {
            status.setText("No evidence yet. Take a quiz on this course.");
        } else if (!"Diagnosing...".equals(status.getText())) {
            status.setText(" ");
        }
    }

    /** Runs when the button is clicked; package-private so tests can run it. */
    void runDiagnosis() {
        long courseId = course.getId();
        diagnose.setEnabled(false);
        status.setText("Diagnosing...");
        runInBackground(() -> facade.diagnoseWeakConcepts(courseId), text -> {
            status.setText(" ");
            diagnose.setEnabled(true);
            diagnosis.setText(text);
            diagnosis.setCaretPosition(0);
        }, error -> {
            status.setText(" ");
            diagnose.setEnabled(true);
            diagnosis.setText("The diagnosis is unavailable: " + error.getMessage());
            diagnosis.setCaretPosition(0);
        });
    }

    /** Draws the mastery cell: a coloured bar with the score beside it, or a dash without enough evidence. */
    private static final class MasteryBar extends JComponent implements TableCellRenderer {

        private static final int SCORE_WIDTH = 34;
        private static final int MARGIN = 4;

        private ConceptMastery mastery;
        private JTable table;
        private boolean selected;

        @Override
        public Component getTableCellRendererComponent(JTable owner, Object value, boolean isSelected, boolean hasFocus,
                                                       int row, int column) {
            mastery = (ConceptMastery) value;
            table = owner;
            selected = isSelected;
            setFont(owner.getFont());
            return this;
        }

        @Override
        protected void paintComponent(Graphics g) {
            g.setColor(selected ? table.getSelectionBackground() : table.getBackground());
            g.fillRect(0, 0, getWidth(), getHeight());
            g.setFont(getFont());
            g.setColor(selected ? table.getSelectionForeground() : table.getForeground());
            FontMetrics metrics = g.getFontMetrics();
            int textY = (getHeight() + metrics.getAscent() - metrics.getDescent()) / 2;
            if (mastery.insufficientData()) {
                g.drawString("-", MARGIN, textY);
                return;
            }
            g.drawString(String.format("%.0f", mastery.score()), MARGIN, textY);
            int barWidth = Math.max(0, getWidth() - SCORE_WIDTH - MARGIN);
            int barHeight = getHeight() - 2 * MARGIN;
            g.setColor(Theme.MASTERY_BAR_BACKGROUND);
            g.fillRect(SCORE_WIDTH, MARGIN, barWidth, barHeight);
            g.setColor(mastery.weak() ? Theme.MASTERY_WEAK : Theme.MASTERY_STRONG);
            g.fillRect(SCORE_WIDTH, MARGIN, (int) (barWidth * mastery.score() / 100), barHeight);
        }
    }
}
