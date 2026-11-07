package studybuddy.presentation.gui;

import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.List;

import javax.swing.BorderFactory;
import javax.swing.DefaultListModel;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JTextArea;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;

import studybuddy.application.ContentGenerationException;
import studybuddy.application.StudyAgentFacade;
import studybuddy.domain.Answer;
import studybuddy.domain.Citation;

/** F03: ask a question about the selected course; double-click a source to read the excerpt. */
public class AskPanel extends FeaturePanel {

    private final JTextArea question = new JTextArea(3, 60);
    private final JButton ask = new JButton("Ask");
    private final JCheckBox allowGeneral = new JCheckBox("Allow general knowledge");
    private final JLabel status = new JLabel(" ");
    private final JTextArea answer = new JTextArea();
    private final DefaultListModel<Citation> sourcesModel = new DefaultListModel<>();
    private final JList<Citation> sources = new JList<>(sourcesModel);
    private boolean busy;

    public AskPanel(StudyAgentFacade facade) {
        super(facade);
        setLayout(new BorderLayout(0, 6));
        setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));

        question.setLineWrap(true);
        question.setWrapStyleWord(true);
        question.getDocument().addDocumentListener(new DocumentListener() {
            @Override
            public void insertUpdate(DocumentEvent e) {
                updateAskButton();
            }

            @Override
            public void removeUpdate(DocumentEvent e) {
                updateAskButton();
            }

            @Override
            public void changedUpdate(DocumentEvent e) {
                updateAskButton();
            }
        });
        ask.addActionListener(e -> askQuestion());

        JPanel controls = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        controls.add(ask);
        controls.add(allowGeneral);
        controls.add(status);
        JPanel top = new JPanel(new BorderLayout(0, 4));
        top.add(new JScrollPane(question), BorderLayout.CENTER);
        top.add(controls, BorderLayout.SOUTH);
        add(top, BorderLayout.NORTH);

        answer.setEditable(false);
        answer.setLineWrap(true);
        answer.setWrapStyleWord(true);
        sources.setCellRenderer((list, c, index, selected, focus) -> {
            JLabel label = new JLabel("[" + c.excerptNumber() + "] " + c.materialTitle() + ", " + c.pageLabel());
            label.setOpaque(true);
            label.setBackground(selected ? list.getSelectionBackground() : list.getBackground());
            label.setForeground(selected ? list.getSelectionForeground() : list.getForeground());
            return label;
        });
        sources.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() == 2 && sources.getSelectedValue() != null) {
                    showExcerpt(sources.getSelectedValue());
                }
            }
        });
        JPanel sourcesPanel = new JPanel(new BorderLayout());
        sourcesPanel.add(new JLabel("Sources (double-click to read):"), BorderLayout.NORTH);
        sourcesPanel.add(new JScrollPane(sources), BorderLayout.CENTER);
        JSplitPane split = new JSplitPane(JSplitPane.VERTICAL_SPLIT, new JScrollPane(answer), sourcesPanel);
        split.setResizeWeight(0.7);
        add(split, BorderLayout.CENTER);
        updateAskButton();
    }

    @Override
    public String title() {
        return "Ask";
    }

    @Override
    public void refresh() {
        updateAskButton();
    }

    private void updateAskButton() {
        ask.setEnabled(course != null && !busy && !question.getText().isBlank());
    }

    private void askQuestion() {
        long courseId = course.getId();
        String text = question.getText();
        boolean general = allowGeneral.isSelected();
        busy = true;
        updateAskButton();
        status.setText("Thinking...");
        answer.setText("");
        sourcesModel.clear();
        runInBackground(() -> facade.askQuestion(courseId, text, general), this::showAnswer, error -> {
            finish();
            showError(error.getMessage());
            if (error instanceof ContentGenerationException failed) {
                answer.setText("The answer could not be generated, but these excerpts look relevant:");
                showSources(failed.getExcerpts());
            }
        });
    }

    private void showAnswer(Answer result) {
        finish();
        StringBuilder text = new StringBuilder();
        if (result.generalKnowledge()) {
            text.append("[Not from your course materials]\n\n");
        }
        text.append(result.text());
        if (result.grounded() && result.partiallyUnverified()) {
            text.append("\n\n(Partially unverified: some statements could not be matched to a provided excerpt.)");
        }
        answer.setText(text.toString());
        answer.setCaretPosition(0);
        showSources(result.citations());
    }

    private void showSources(List<Citation> citations) {
        sourcesModel.clear();
        citations.forEach(sourcesModel::addElement);
    }

    private void finish() {
        busy = false;
        status.setText(" ");
        updateAskButton();
    }

    private void showExcerpt(Citation citation) {
        JTextArea text = new JTextArea(citation.excerpt(), 12, 50);
        text.setLineWrap(true);
        text.setWrapStyleWord(true);
        text.setEditable(false);
        JOptionPane.showMessageDialog(this, new JScrollPane(text),
                citation.materialTitle() + ", " + citation.pageLabel(), JOptionPane.PLAIN_MESSAGE);
    }
}
