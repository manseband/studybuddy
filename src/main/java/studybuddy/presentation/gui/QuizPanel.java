package studybuddy.presentation.gui;

import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.GridLayout;
import java.awt.Insets;
import java.awt.Rectangle;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.function.Supplier;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.ButtonGroup;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JProgressBar;
import javax.swing.JRadioButton;
import javax.swing.JScrollPane;
import javax.swing.JSpinner;
import javax.swing.JTable;
import javax.swing.JTextArea;
import javax.swing.Scrollable;
import javax.swing.SpinnerNumberModel;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import javax.swing.table.DefaultTableModel;

import studybuddy.application.StudyAgentFacade;
import studybuddy.domain.AttemptStatus;
import studybuddy.domain.Citation;
import studybuddy.domain.Concept;
import studybuddy.domain.Course;
import studybuddy.domain.Difficulty;
import studybuddy.domain.Explanation;
import studybuddy.domain.GradeResult;
import studybuddy.domain.Material;
import studybuddy.domain.MultipleChoiceQuestion;
import studybuddy.domain.Question;
import studybuddy.domain.QuestionType;
import studybuddy.domain.Quiz;
import studybuddy.domain.QuizAttempt;
import studybuddy.domain.QuizRequest;
import studybuddy.domain.TrueFalseQuestion;

/**
 * F06, F07: the Quizzes tab has three views: the list of past quizzes, taking a quiz, and the results of
 * an attempt. Grading can call the AI, so generating and submitting run in the background.
 */
public class QuizPanel extends FeaturePanel {

    static final String LIST_VIEW = "list";
    static final String TAKE_VIEW = "take";
    static final String RESULTS_VIEW = "results";
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    private final CardLayout cards = new CardLayout();
    private final JPanel views = new JPanel(cards);
    private String currentView = LIST_VIEW;

    private final DefaultTableModel model = new DefaultTableModel(
            new Object[] {"Quiz", "Created", "Difficulty", "Questions", "Attempts", "Latest score"}, 0) {
        @Override
        public boolean isCellEditable(int row, int column) {
            return false;
        }
    };
    private final JTable table = new StudyTable(model);
    private final JButton newQuiz = new JButton("New Quiz");
    private final JButton take = new JButton("Take Quiz");
    private final JButton viewResults = new JButton("View Results");
    private final JProgressBar progress = new JProgressBar();
    private final JLabel listStatus = new JLabel(" ");
    private List<Quiz> quizzes = List.of();
    private List<QuizAttempt> attempts = List.of();

    private final JLabel takeTitle = new JLabel(" ");
    private final Column takeColumn = new Column();
    private final JScrollPane takeScroll = new JScrollPane(takeColumn);
    private final JButton submit = new JButton("Submit");
    private final JButton cancelTake = new JButton("Cancel");
    private final JLabel takeStatus = new JLabel(" ");
    private final List<Supplier<String>> inputs = new ArrayList<>();
    private Quiz takingQuiz;
    private QuizAttempt takingAttempt;

    private final JLabel resultsTitle = new JLabel(" ");
    private final Column resultsColumn = new Column();
    private final JScrollPane resultsScroll = new JScrollPane(resultsColumn);
    private final JButton regrade = new JButton("Grade Pending Answers");
    private final JLabel resultsStatus = new JLabel(" ");
    private Quiz resultsQuiz;
    private long resultsAttemptId;

    public QuizPanel(StudyAgentFacade facade) {
        super(facade);
        setLayout(new BorderLayout());
        views.add(buildListView(), LIST_VIEW);
        views.add(buildTakeView(), TAKE_VIEW);
        views.add(buildResultsView(), RESULTS_VIEW);
        add(views, BorderLayout.CENTER);
        updateListButtons();
    }

    @Override
    public String title() {
        return "Quizzes";
    }

    /** Selecting another course leaves a quiz in progress; the same course keeps the current view. */
    @Override
    public void setCourse(Course newCourse) {
        boolean sameCourse = course != null && newCourse != null && course.getId() == newCourse.getId();
        if (!sameCourse) {
            showView(LIST_VIEW);
        }
        super.setCourse(newCourse);
    }

    /** Reloads the list of quizzes; it never disturbs a quiz being taken. */
    @Override
    public void refresh() {
        if (course == null) {
            fillList(List.of(), List.of());
            return;
        }
        long courseId = course.getId();
        record Loaded(List<Quiz> quizzes, List<QuizAttempt> attempts) {
        }
        runInBackground(() -> new Loaded(facade.listQuizzes(courseId), facade.listAttempts(courseId)),
                loaded -> fillList(loaded.quizzes(), loaded.attempts()));
    }

    // ----- list view -----

    private JPanel buildListView() {
        JPanel view = new JPanel(new BorderLayout(0, 6));
        view.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));
        JPanel top = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        newQuiz.addActionListener(e -> openNewQuizDialog());
        top.add(newQuiz);
        take.addActionListener(e -> {
            Quiz quiz = selectedQuiz();
            if (quiz != null) {
                openQuiz(quiz);
            }
        });
        top.add(take);
        viewResults.addActionListener(e -> showLatestResults());
        top.add(viewResults);
        progress.setIndeterminate(true);
        progress.setVisible(false);
        top.add(progress);
        top.add(listStatus);
        view.add(top, BorderLayout.NORTH);
        table.getSelectionModel().addListSelectionListener(e -> updateListButtons());
        view.add(new JScrollPane(table), BorderLayout.CENTER);
        return view;
    }

    private void fillList(List<Quiz> newQuizzes, List<QuizAttempt> newAttempts) {
        long selectedId = selectedQuiz() == null ? 0 : selectedQuiz().getId();
        quizzes = newQuizzes;
        attempts = newAttempts;
        model.setRowCount(0);
        for (int row = 0; row < quizzes.size(); row++) {
            Quiz quiz = quizzes.get(row);
            QuizAttempt latest = latestAttempt(quiz);
            model.addRow(new Object[] {"#" + quiz.getId(), TIME.format(quiz.getCreatedAt()), difficultyLabel(quiz),
                    quiz.size(), attempts.stream().filter(a -> a.getQuizId() == quiz.getId()).count(),
                    latest == null ? "" : scoreLabel(latest)});
            if (quiz.getId() == selectedId) {
                table.setRowSelectionInterval(row, row);
            }
        }
        updateListButtons();
    }

    private void updateListButtons() {
        Quiz quiz = selectedQuiz();
        newQuiz.setEnabled(course != null);
        take.setEnabled(quiz != null);
        viewResults.setEnabled(quiz != null && latestAttempt(quiz) != null);
    }

    private Quiz selectedQuiz() {
        int row = table.getSelectedRow();
        return row < 0 || row >= quizzes.size() ? null : quizzes.get(row);
    }

    /** Attempts are listed most recent first. */
    private QuizAttempt latestAttempt(Quiz quiz) {
        return attempts.stream().filter(a -> a.getQuizId() == quiz.getId()).findFirst().orElse(null);
    }

    private void showLatestResults() {
        Quiz quiz = selectedQuiz();
        QuizAttempt attempt = quiz == null ? null : latestAttempt(quiz);
        if (attempt != null) {
            showResults(quiz, attempt);
        }
    }

    private static String difficultyLabel(Quiz quiz) {
        return capitalize(quiz.getDifficulty().name());
    }

    private static String capitalize(String word) {
        return word.charAt(0) + word.substring(1).toLowerCase();
    }

    private static String scoreLabel(QuizAttempt attempt) {
        return String.format("%.1f / %d (%.0f%%)", attempt.getTotalScore(), attempt.getMaxScore(),
                attempt.getPercentage());
    }

    // ----- new quiz dialog -----

    private void openNewQuizDialog() {
        long courseId = course.getId();
        record Scope(List<Material> materials, List<Concept> concepts) {
        }
        runInBackground(() -> new Scope(facade.listMaterials(courseId), facade.listConcepts(courseId)),
                scope -> askForQuizSettings(courseId, scope.materials(), scope.concepts()));
    }

    private void askForQuizSettings(long courseId, List<Material> materials, List<Concept> concepts) {
        JList<Material> materialList = multiSelectList(materials);
        JList<Concept> conceptList = multiSelectList(concepts);
        JSpinner count = new JSpinner(new SpinnerNumberModel(10, QuizRequest.MIN_QUESTIONS, QuizRequest.MAX_QUESTIONS, 1));
        JCheckBox mcq = new JCheckBox("Multiple choice", true);
        JCheckBox trueFalse = new JCheckBox("True/false", true);
        JCheckBox shortAnswer = new JCheckBox("Short answer", true);
        JComboBox<String> difficulty = new JComboBox<>(new String[] {"Easy", "Medium", "Hard"});
        difficulty.setSelectedItem("Medium");

        JPanel scope = new JPanel(new GridLayout(1, 2, 8, 0));
        scope.add(titled("Materials", new JScrollPane(materialList)));
        scope.add(titled("Concepts", new JScrollPane(conceptList)));
        scope.setPreferredSize(new Dimension(480, 170));
        JPanel settings = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        settings.add(new JLabel("Questions:"));
        settings.add(count);
        settings.add(new JLabel("Difficulty:"));
        settings.add(difficulty);
        JPanel typeBoxes = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        typeBoxes.add(new JLabel("Types:"));
        typeBoxes.add(mcq);
        typeBoxes.add(trueFalse);
        typeBoxes.add(shortAnswer);
        JPanel options = new JPanel(new GridLayout(0, 1, 0, 6));
        options.add(settings);
        options.add(typeBoxes);
        JPanel form = new JPanel(new BorderLayout(0, 8));
        form.add(new JLabel(SCOPE_HINT), BorderLayout.NORTH);
        form.add(scope, BorderLayout.CENTER);
        form.add(options, BorderLayout.SOUTH);

        if (JOptionPane.showConfirmDialog(this, form, "New Quiz", JOptionPane.OK_CANCEL_OPTION,
                JOptionPane.PLAIN_MESSAGE) != JOptionPane.OK_OPTION) {
            return;
        }
        Set<QuestionType> types = EnumSet.noneOf(QuestionType.class);
        if (mcq.isSelected()) {
            types.add(QuestionType.MULTIPLE_CHOICE);
        }
        if (trueFalse.isSelected()) {
            types.add(QuestionType.TRUE_FALSE);
        }
        if (shortAnswer.isSelected()) {
            types.add(QuestionType.SHORT_ANSWER);
        }
        try {
            generate(new QuizRequest(courseId,
                    materialList.getSelectedValuesList().stream().map(Material::getId).toList(),
                    conceptList.getSelectedValuesList().stream().map(Concept::getId).toList(),
                    (Integer) count.getValue(), types, Difficulty.fromString((String) difficulty.getSelectedItem())));
        } catch (IllegalArgumentException e) {
            showError(e.getMessage());
        }
    }

    private void generate(QuizRequest request) {
        newQuiz.setEnabled(false);
        progress.setVisible(true);
        listStatus.setText("Generating quiz...");
        runInBackground(() -> facade.generateQuiz(request), quiz -> {
            finishGenerating();
            if (course == null || course.getId() != request.courseId()) {
                return;
            }
            openQuiz(quiz);
            if (quiz.size() < request.questionCount()) {
                showInfo("Only " + quiz.size() + " of the " + request.questionCount()
                        + " requested questions could be generated from this material.");
            }
        }, error -> {
            finishGenerating();
            showError(error.getMessage());
        });
    }

    private void finishGenerating() {
        progress.setVisible(false);
        listStatus.setText(" ");
        updateListButtons();
    }

    // ----- taking a quiz -----

    private JPanel buildTakeView() {
        JPanel view = new JPanel(new BorderLayout(0, 6));
        view.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));
        takeTitle.setFont(takeTitle.getFont().deriveFont(Font.BOLD));
        view.add(takeTitle, BorderLayout.NORTH);
        takeScroll.getVerticalScrollBar().setUnitIncrement(16);
        view.add(takeScroll, BorderLayout.CENTER);
        JPanel bottom = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        submit.addActionListener(e -> confirmAndSubmit());
        bottom.add(submit);
        cancelTake.addActionListener(e -> showView(LIST_VIEW));
        bottom.add(cancelTake);
        bottom.add(takeStatus);
        view.add(bottom, BorderLayout.SOUTH);
        return view;
    }

    /** Opens a new attempt at the quiz in the quiz-taking view. */
    void openQuiz(Quiz quiz) {
        takingQuiz = quiz;
        takingAttempt = facade.startAttempt(quiz.getId());
        inputs.clear();
        takeColumn.clear();
        takeTitle.setText("Quiz #" + quiz.getId() + ": " + quiz.size() + " question(s), "
                + difficultyLabel(quiz).toLowerCase());
        int number = 1;
        for (Question question : quiz.getQuestions()) {
            takeColumn.addRow(questionInput(number++, question));
        }
        takeColumn.finish();
        takeStatus.setText(" ");
        submit.setEnabled(true);
        cancelTake.setEnabled(true);
        showView(TAKE_VIEW);
        scrollToTop(takeScroll);
    }

    /** The question and the right kind of input for its type; records how to read the answer. */
    private JPanel questionInput(int number, Question question) {
        JPanel card = questionCard(number + ". " + question.getStem());
        switch (question) {
            case MultipleChoiceQuestion mcq -> {
                List<String> options = mcq.getOptions();
                List<String> labels = new ArrayList<>();
                List<String> values = new ArrayList<>();
                for (int i = 0; i < options.size(); i++) {
                    labels.add(MultipleChoiceQuestion.letter(i) + ") " + options.get(i));
                    values.add(MultipleChoiceQuestion.letter(i));
                }
                inputs.add(radioButtons(card, labels, values));
            }
            case TrueFalseQuestion _ ->
                    inputs.add(radioButtons(card, List.of("True", "False"), List.of("true", "false")));
            default -> {
                JTextArea answer = new JTextArea(3, 40);
                answer.setLineWrap(true);
                answer.setWrapStyleWord(true);
                addToCard(card, new JScrollPane(answer));
                inputs.add(answer::getText);
            }
        }
        return card;
    }

    /** Adds a group of radio buttons to the card; the supplier gives the chosen value, or "" for none. */
    private static Supplier<String> radioButtons(JPanel card, List<String> labels, List<String> values) {
        ButtonGroup group = new ButtonGroup();
        List<JRadioButton> buttons = new ArrayList<>();
        JPanel column = new JPanel(new GridLayout(0, 1));
        column.setOpaque(false);
        for (String label : labels) {
            JRadioButton button = new JRadioButton(label);
            button.setFont(Theme.textFont());
            button.setOpaque(false);
            group.add(button);
            buttons.add(button);
            column.add(button);
        }
        addToCard(card, column);
        return () -> {
            for (int i = 0; i < buttons.size(); i++) {
                if (buttons.get(i).isSelected()) {
                    return values.get(i);
                }
            }
            return "";
        };
    }

    private void confirmAndSubmit() {
        long blank = inputs.stream().filter(input -> input.get().isBlank()).count();
        if (blank > 0 && JOptionPane.showConfirmDialog(this, blank + " question(s) are unanswered and will score 0. "
                + "Submit anyway?", "Submit Quiz", JOptionPane.OK_CANCEL_OPTION) != JOptionPane.OK_OPTION) {
            return;
        }
        submitAnswers();
    }

    /** Sends the answers for grading and shows the results. */
    void submitAnswers() {
        Quiz quiz = takingQuiz;
        QuizAttempt attempt = takingAttempt;
        List<Question> questions = quiz.getQuestions();
        for (int i = 0; i < questions.size(); i++) {
            attempt.answer(questions.get(i).getId(), inputs.get(i).get());
        }
        submit.setEnabled(false);
        cancelTake.setEnabled(false);
        takeStatus.setText("Grading...");
        runInBackground(() -> facade.submitQuiz(attempt), graded -> showResults(quiz, graded), error -> {
            takeStatus.setText(" ");
            showView(LIST_VIEW);
            showError(error.getMessage());
        });
    }

    // ----- results -----

    private JPanel buildResultsView() {
        JPanel view = new JPanel(new BorderLayout(0, 6));
        view.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));
        JPanel top = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        JButton back = new JButton("Back to Quizzes");
        back.addActionListener(e -> showView(LIST_VIEW));
        top.add(back);
        regrade.addActionListener(e -> regradePending());
        top.add(regrade);
        resultsTitle.setFont(resultsTitle.getFont().deriveFont(Font.BOLD));
        top.add(resultsTitle);
        top.add(resultsStatus);
        view.add(top, BorderLayout.NORTH);
        resultsScroll.getVerticalScrollBar().setUnitIncrement(16);
        view.add(resultsScroll, BorderLayout.CENTER);
        return view;
    }

    private void showResults(Quiz quiz, QuizAttempt attempt) {
        resultsColumn.clear();
        resultsTitle.setText("Score: " + scoreLabel(attempt));
        resultsStatus.setText(attempt.getStatus() == AttemptStatus.PENDING_GRADING
                ? "Some answers could not be graded yet." : " ");
        regrade.setVisible(attempt.getStatus() == AttemptStatus.PENDING_GRADING);
        resultsQuiz = quiz;
        resultsAttemptId = attempt.getId();
        int number = 1;
        for (Question question : quiz.getQuestions()) {
            resultsColumn.addRow(resultCard(number++, question, quiz, attempt));
        }
        resultsColumn.finish();
        showView(RESULTS_VIEW);
        scrollToTop(resultsScroll);
    }

    private JPanel resultCard(int number, Question question, Quiz quiz, QuizAttempt attempt) {
        GradeResult result = attempt.resultFor(question.getId()).orElseThrow();
        JPanel card = questionCard(number + ". " + question.getStem());

        JLabel mark = new JLabel(markText(result));
        mark.setFont(Theme.textFont().deriveFont(Font.BOLD));
        mark.setForeground(result.pending() ? Theme.ANSWER_PENDING
                : result.correct() ? Theme.ANSWER_CORRECT : Theme.ANSWER_WRONG);
        addToCard(card, mark);

        addToCard(card, wrappedText("Your answer: " + answerText(question, attempt.answerFor(question.getId()))));
        addToCard(card, wrappedText("Correct answer: " + question.correctAnswerText()));
        if (!result.feedback().isBlank() && question.getType() == QuestionType.SHORT_ANSWER) {
            addToCard(card, wrappedText(result.feedback()));
        }
        addToCard(card, new JLabel("Source: p. " + question.getSourcePage()));
        if (!result.pending()) {
            JPanel explanation = new JPanel();
            explanation.setLayout(new BoxLayout(explanation, BoxLayout.Y_AXIS));
            explanation.setOpaque(false);
            Box buttons = Box.createHorizontalBox();
            JButton explain = new JButton("Explain");
            explain.addActionListener(e -> explain(explain, explanation, attempt.getId(), question.getId()));
            buttons.add(explain);
            if (result.gradedByAI()) {
                JButton dispute = new JButton("Dispute");
                dispute.addActionListener(e -> dispute(quiz, attempt.getId(), question.getId()));
                buttons.add(Box.createHorizontalStrut(6));
                buttons.add(dispute);
            }
            addToCard(card, buttons);
            addToCard(card, explanation);
        }
        return card;
    }

    /** F08: fetches the explanation and shows it inside the question's card. */
    private void explain(JButton button, JPanel holder, long attemptId, long questionId) {
        button.setEnabled(false);
        button.setText("Explaining...");
        runInBackground(() -> facade.explainAnswer(attemptId, questionId), explanation -> {
            button.setText("Explain");
            showExplanation(holder, explanation);
        }, error -> {
            button.setEnabled(true);
            button.setText("Explain");
            holder.removeAll();
            addToCard(holder, wrappedText("The explanation is unavailable: " + error.getMessage()
                    + "\nThe correct answer is shown above."));
            layOutResults();
        });
    }

    private void showExplanation(JPanel holder, Explanation explanation) {
        holder.removeAll();
        addToCard(holder, wrappedText(explanation.text()));
        if (explanation.misconception() != null) {
            addToCard(holder, wrappedText("Likely misconception: " + explanation.misconception()));
        }
        if (explanation.sourceAvailable()) {
            Citation citation = explanation.citation();
            JButton source = new JButton("Read source: " + citation.materialTitle() + ", " + citation.pageLabel());
            source.addActionListener(e -> showExcerpt(citation));
            addToCard(holder, source);
        } else {
            addToCard(holder, new JLabel("No source available: this uses only the answer key."));
        }
        layOutResults();
    }

    private void showExcerpt(Citation citation) {
        JTextArea text = new JTextArea(citation.excerpt(), 12, 50);
        text.setLineWrap(true);
        text.setWrapStyleWord(true);
        text.setEditable(false);
        text.setCaretPosition(0);
        JOptionPane.showMessageDialog(this, new JScrollPane(text),
                citation.materialTitle() + ", " + citation.pageLabel(), JOptionPane.PLAIN_MESSAGE);
    }

    /** New wrapped text only knows its height once its width is set, so this lays out twice. */
    private void layOutResults() {
        resultsColumn.revalidate();
        resultsColumn.repaint();
        SwingUtilities.invokeLater(resultsColumn::revalidate);
    }

    private static String markText(GradeResult result) {
        if (result.pending()) {
            return "Pending grading";
        }
        String mark = String.format("%s (%.2f / 1)", result.correct() ? "Correct" : "Incorrect", result.score());
        return result.overridden() ? mark + ", grade set by you" : mark;
    }

    /** The answer in full: an option letter with its text, and "t" as "True". */
    private static String answerText(Question question, String answer) {
        if (answer.isBlank()) {
            return "(not answered)";
        }
        if (question instanceof MultipleChoiceQuestion mcq && answer.length() == 1) {
            String letter = answer.toUpperCase();
            int index = letter.charAt(0) - 'A';
            if (index >= 0 && index < mcq.getOptions().size()) {
                return letter + ") " + mcq.getOptions().get(index);
            }
        }
        if (question instanceof TrueFalseQuestion) {
            return switch (answer.toLowerCase()) {
                case "t", "true" -> "True";
                case "f", "false" -> "False";
                default -> answer;
            };
        }
        return answer;
    }

    private void dispute(Quiz quiz, long attemptId, long questionId) {
        String input = JOptionPane.showInputDialog(this, "Your grade for this answer, from 0 to 1:", "Dispute",
                JOptionPane.PLAIN_MESSAGE);
        if (input == null) {
            return;
        }
        try {
            double score = Double.parseDouble(input.strip());
            runInBackground(() -> facade.overrideGrade(attemptId, questionId, score),
                    changed -> showResults(quiz, changed));
        } catch (NumberFormatException e) {
            showError("The grade must be a number from 0 to 1.");
        }
    }

    private void regradePending() {
        Quiz quiz = resultsQuiz;
        long attemptId = resultsAttemptId;
        regrade.setEnabled(false);
        resultsStatus.setText("Grading...");
        runInBackground(() -> facade.regradePending(attemptId), updated -> {
            regrade.setEnabled(true);
            showResults(quiz, updated);
        }, error -> {
            regrade.setEnabled(true);
            resultsStatus.setText(" ");
            showError(error.getMessage());
        });
    }

    // ----- shared helpers -----

    private void showView(String view) {
        currentView = view;
        cards.show(views, view);
        if (LIST_VIEW.equals(view)) {
            refresh();
        }
    }

    private static void scrollToTop(JScrollPane scroll) {
        SwingUtilities.invokeLater(() -> scroll.getVerticalScrollBar().setValue(0));
    }

    /** A bordered block that starts with the question's stem; rows are stacked below it. */
    private static JPanel questionCard(String stem) {
        JPanel card = new JPanel();
        card.setLayout(new BoxLayout(card, BoxLayout.Y_AXIS));
        card.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createEtchedBorder(), BorderFactory.createEmptyBorder(8, 8, 8, 8)));
        addToCard(card, wrappedText(stem));
        return card;
    }

    /** BoxLayout only lines rows up on the left if every one of them is left-aligned. */
    private static void addToCard(JPanel card, JComponent row) {
        row.setAlignmentX(LEFT_ALIGNMENT);
        card.add(row);
    }

    /** Which view is showing; used by tests. */
    String currentView() {
        return currentView;
    }

    /** Rows in the quiz list; used by tests. */
    int rowCount() {
        return model.getRowCount();
    }

    /**
     * A vertical stack of cards that follows the width of its scroll pane, so long lines wrap
     * instead of scrolling sideways.
     */
    private static final class Column extends JPanel implements Scrollable {
        private int nextRow;

        Column() {
            super(new GridBagLayout());
            setBorder(BorderFactory.createEmptyBorder(4, 4, 4, 4));
            // a wrapped text area only knows its height once its width is set, so lay out again after resizing
            addComponentListener(new ComponentAdapter() {
                @Override
                public void componentResized(ComponentEvent e) {
                    revalidate();
                }
            });
        }

        void clear() {
            removeAll();
            nextRow = 0;
        }

        void addRow(JComponent row) {
            GridBagConstraints c = new GridBagConstraints();
            c.gridx = 0;
            c.gridy = nextRow++;
            c.weightx = 1;
            c.fill = GridBagConstraints.HORIZONTAL;
            c.insets = new Insets(0, 0, 8, 0);
            add(row, c);
        }

        /** Fills the leftover height so the cards stay at the top. */
        void finish() {
            GridBagConstraints c = new GridBagConstraints();
            c.gridx = 0;
            c.gridy = nextRow;
            c.weighty = 1;
            add(new JPanel(), c);
            revalidate();
            repaint();
        }

        @Override
        public Dimension getPreferredScrollableViewportSize() {
            return getPreferredSize();
        }

        @Override
        public int getScrollableUnitIncrement(Rectangle visible, int orientation, int direction) {
            return 16;
        }

        @Override
        public int getScrollableBlockIncrement(Rectangle visible, int orientation, int direction) {
            return orientation == SwingConstants.VERTICAL ? visible.height : visible.width;
        }

        @Override
        public boolean getScrollableTracksViewportWidth() {
            return true;
        }

        @Override
        public boolean getScrollableTracksViewportHeight() {
            return false;
        }
    }
}
