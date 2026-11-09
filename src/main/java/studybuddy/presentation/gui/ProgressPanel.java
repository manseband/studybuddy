package studybuddy.presentation.gui;

import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.FlowLayout;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.function.Consumer;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JFileChooser;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JTabbedPane;
import javax.swing.JTable;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.filechooser.FileNameExtensionFilter;
import javax.swing.table.DefaultTableModel;
import javax.swing.table.TableCellRenderer;

import studybuddy.application.StudyAgentFacade;
import studybuddy.domain.ActivityRecord;
import studybuddy.domain.ActivityType;
import studybuddy.domain.ConceptMastery;
import studybuddy.domain.Course;
import studybuddy.domain.ProgressReport;
import studybuddy.domain.ReportFormat;

/**
 * F08 and F10: the course's average score and streak above two tabs, one with concepts ranked by mastery (weakest
 * first) and an optional AI diagnosis, the other with the study history. Everything updates on its own: any
 * event from a service refreshes this panel.
 */
public class ProgressPanel extends FeaturePanel {

    private static final int MASTERY_COLUMN = 1;
    private static final String ALL_ACTIVITY = "All activity";

    private final JLabel summary = new JLabel(" ");
    private final JButton export = new JButton("Export Report");
    private final JLabel exportStatus = new JLabel(" ");

    private final DefaultTableModel model = readOnlyModel("Concept", "Mastery", "Evidence", "Trend", "Status");
    private final JTable table = new StudyTable(model);
    private final JButton diagnose = new JButton("Diagnose Weak Concepts");
    private final JLabel status = new JLabel(" ");
    private final JTextArea diagnosis = new JTextArea(7, 40);

    private final DefaultTableModel historyModel = readOnlyModel("Date", "Activity", "Details", "Result");
    private final JTable historyTable = new StudyTable(historyModel);
    private final JTextField from = new JTextField(9);
    private final JTextField to = new JTextField(9);
    private final JComboBox<String> type = new JComboBox<>();
    private final JLabel historyStatus = new JLabel(" ");
    /** Background loads can finish out of order, so only the newest request of each kind may update the panel. */
    private int latestReportRequest;
    private int latestHistoryRequest;

    public ProgressPanel(StudyAgentFacade facade) {
        super(facade);
        setLayout(new BorderLayout(0, 6));
        setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));

        add(buildSummaryBar(), BorderLayout.NORTH);
        JTabbedPane views = new JTabbedPane();
        views.addTab("Concept Mastery", buildMasteryView());
        views.addTab("Study History", buildHistoryView());
        add(views, BorderLayout.CENTER);
        export.setEnabled(false);
    }

    private static DefaultTableModel readOnlyModel(String... columns) {
        return new DefaultTableModel(columns, 0) {
            @Override
            public boolean isCellEditable(int row, int column) {
                return false;
            }
        };
    }

    private JPanel buildSummaryBar() {
        JPanel bar = new JPanel(new BorderLayout(8, 0));
        bar.add(summary, BorderLayout.WEST);
        JPanel right = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        export.addActionListener(e -> chooseExportFile());
        right.add(exportStatus);
        right.add(export);
        bar.add(right, BorderLayout.EAST);
        return bar;
    }

    private JPanel buildMasteryView() {
        JPanel view = new JPanel(new BorderLayout(0, 6));
        JPanel top = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 4));
        diagnose.addActionListener(e -> runDiagnosis());
        top.add(diagnose);
        top.add(status);
        view.add(top, BorderLayout.NORTH);

        table.getColumnModel().getColumn(MASTERY_COLUMN).setCellRenderer(new MasteryBar());
        diagnosis.setEditable(false);
        diagnosis.setLineWrap(true);
        diagnosis.setWrapStyleWord(true);
        JSplitPane split = new JSplitPane(JSplitPane.VERTICAL_SPLIT, new JScrollPane(table), new JScrollPane(diagnosis));
        split.setResizeWeight(0.7);
        view.add(split, BorderLayout.CENTER);
        diagnose.setEnabled(false);
        return view;
    }

    private JPanel buildHistoryView() {
        JPanel view = new JPanel(new BorderLayout(0, 6));
        JPanel filters = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 4));
        filters.add(new JLabel("From:"));
        filters.add(from);
        filters.add(new JLabel("To:"));
        filters.add(to);
        type.addItem(ALL_ACTIVITY);
        for (ActivityType activityType : ActivityType.values()) {
            type.addItem(activityType.label());
        }
        filters.add(type);
        JButton apply = new JButton("Filter");
        apply.addActionListener(e -> loadHistory());
        filters.add(apply);
        JButton clear = new JButton("Clear");
        clear.addActionListener(e -> clearHistoryFilter());
        filters.add(clear);
        filters.add(historyStatus);
        view.add(filters, BorderLayout.NORTH);

        int[] widths = {120, 130, 420, 230};
        for (int column = 0; column < widths.length; column++) {
            historyTable.getColumnModel().getColumn(column).setPreferredWidth(widths[column]);
        }
        from.setToolTipText("YYYY-MM-DD");
        to.setToolTipText("YYYY-MM-DD");
        view.add(new JScrollPane(historyTable), BorderLayout.CENTER);
        return view;
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
        exportStatus.setText(" ");
        super.setCourse(newCourse);
    }

    @Override
    public void refresh() {
        export.setEnabled(course != null);
        int request = ++latestReportRequest;
        if (course == null) {
            latestHistoryRequest++;
            summary.setText(" ");
            showMastery(List.of());
            showHistory(List.of());
            historyStatus.setText(" ");
            return;
        }
        long courseId = course.getId();
        runInBackground(() -> facade.getProgress(courseId), report -> {
            if (request == latestReportRequest) {
                showReport(report);
            }
        });
        loadHistory();
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

    /** The summary line; used by tests. */
    String summaryText() {
        return summary.getText();
    }

    /** History rows currently shown; used by tests. */
    int historyRowCount() {
        return historyModel.getRowCount();
    }

    /** The activity column of a history row; used by tests. */
    String activityAt(int row) {
        return (String) historyModel.getValueAt(row, 1);
    }

    /** The message beside the history filter; used by tests. */
    String historyMessage() {
        return historyStatus.getText();
    }

    /** The message beside the export button; used by tests. */
    String exportMessage() {
        return exportStatus.getText();
    }

    /** Sets the history filter as if typed in; used by tests. A null type means all activity. */
    void setHistoryFilter(String fromDate, String toDate, ActivityType activityType) {
        from.setText(fromDate);
        to.setText(toDate);
        type.setSelectedItem(activityType == null ? ALL_ACTIVITY : activityType.label());
    }

    private void showReport(ProgressReport report) {
        summary.setText("Average score: " + report.averageScoreText() + "   |   Current streak: " + report.streakText());
        showMastery(report.mastery());
    }

    private void showMastery(List<ConceptMastery> mastery) {
        model.setRowCount(0);
        for (ConceptMastery m : mastery) {
            model.addRow(new Object[] {m.conceptName(), m, m.evidenceCount(), m.trend().arrow(), m.statusLabel()});
        }
        diagnose.setEnabled(mastery.stream().anyMatch(ConceptMastery::weak));
        if (mastery.isEmpty()) {
            status.setText(course == null ? " " : "No concepts yet. Extract them in the Concepts tab first.");
        } else if (mastery.stream().allMatch(m -> m.evidenceCount() == 0)) {
            status.setText("No evidence yet. Take a quiz or review flashcards on this course.");
        } else if (!"Diagnosing...".equals(status.getText())) {
            status.setText(" ");
        }
    }

    /** Runs when the Filter button is clicked and on every refresh; package-private so tests can run it. */
    void loadHistory() {
        if (course == null) {
            return;
        }
        LocalDate fromDate;
        LocalDate toDate;
        try {
            fromDate = parseDate(from.getText());
            toDate = parseDate(to.getText());
        } catch (DateTimeParseException e) {
            historyStatus.setText("Enter dates as YYYY-MM-DD.");
            return;
        }
        int typeIndex = type.getSelectedIndex();
        ActivityType activityType = typeIndex == 0 ? null : ActivityType.values()[typeIndex - 1];
        boolean filtered = fromDate != null || toDate != null || activityType != null;
        long courseId = course.getId();
        int request = ++latestHistoryRequest;
        runInBackground(() -> facade.getHistory(courseId, fromDate, toDate, activityType), history -> {
            if (request == latestHistoryRequest) {
                showHistory(history);
                historyStatus.setText(!history.isEmpty() ? " " : filtered ? "No activity matches this filter."
                        : "No activity yet. Upload a material, ask a question or take a quiz to get started.");
            }
        }, error -> {
            if (request == latestHistoryRequest) {
                historyStatus.setText(error.getMessage());
            }
        });
    }

    private static LocalDate parseDate(String text) {
        return text.isBlank() ? null : LocalDate.parse(text.strip());
    }

    private void showHistory(List<ActivityRecord> history) {
        historyModel.setRowCount(0);
        for (ActivityRecord r : history) {
            historyModel.addRow(new Object[] {r.dateLabel(), r.activity(), r.details(), r.result()});
        }
    }

    private void clearHistoryFilter() {
        setHistoryFilter("", "", null);
        loadHistory();
    }

    /** Asks where to save the report. It keeps asking if the chosen location turns out not to be writable. */
    private void chooseExportFile() {
        FileNameExtensionFilter markdown = new FileNameExtensionFilter("Markdown report (*.md)", "md");
        FileNameExtensionFilter csv = new FileNameExtensionFilter("CSV report (*.csv)", "csv");
        JFileChooser chooser = new JFileChooser(System.getProperty("user.dir"));
        chooser.setAcceptAllFileFilterUsed(false);
        chooser.addChoosableFileFilter(markdown);
        chooser.addChoosableFileFilter(csv);
        chooser.setFileFilter(markdown);
        chooser.setSelectedFile(new File(course.getCode().replaceAll("[^\\w.-]+", "-") + "-progress.md"));
        if (chooser.showSaveDialog(this) != JFileChooser.APPROVE_OPTION) {
            return;
        }
        ReportFormat format = chooser.getFileFilter() == csv ? ReportFormat.CSV : ReportFormat.MARKDOWN;
        Path file = withExtension(chooser.getSelectedFile().toPath(), format);
        if (Files.exists(file) && JOptionPane.showConfirmDialog(this, file.getFileName() + " already exists. Replace it?",
                "Export Report", JOptionPane.OK_CANCEL_OPTION) != JOptionPane.OK_OPTION) {
            return;
        }
        export(file, format, error -> {
            showError(error);
            chooseExportFile();
        });
    }

    /** The file name with the format's extension in place of any report extension it already has. */
    private static Path withExtension(Path file, ReportFormat format) {
        String base = file.getFileName().toString().replaceFirst("(?i)\\.(md|csv)$", "");
        return file.resolveSibling(base + "." + format.extension());
    }

    /** Saves the report; package-private so tests can run it without the file dialog. */
    void export(Path file, ReportFormat format, Consumer<String> onFailure) {
        long courseId = course.getId();
        export.setEnabled(false);
        exportStatus.setText("Exporting...");
        runInBackground(() -> facade.exportReport(courseId, format, file), saved -> {
            export.setEnabled(true);
            exportStatus.setText("Saved " + saved.getFileName());
        }, error -> {
            export.setEnabled(true);
            exportStatus.setText(" ");
            onFailure.accept(error.getMessage());
        });
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
