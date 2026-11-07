package studybuddy.presentation.gui;

import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.GridLayout;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;

import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JProgressBar;
import javax.swing.JScrollPane;
import javax.swing.JSpinner;
import javax.swing.JSplitPane;
import javax.swing.JTable;
import javax.swing.JTextArea;
import javax.swing.SpinnerNumberModel;
import javax.swing.table.DefaultTableModel;
import javax.swing.table.TableCellRenderer;

import studybuddy.application.StudyAgentFacade;
import studybuddy.domain.Concept;
import studybuddy.domain.Course;
import studybuddy.domain.Flashcard;
import studybuddy.domain.Material;
import studybuddy.domain.Rating;

/**
 * F04: the Flashcards tab has three views: the course's decks, an editable preview of newly generated
 * cards, and review of the due cards. Review needs no AI, so it works without an API key.
 */
public class FlashcardPanel extends FeaturePanel {

    static final String DECKS_VIEW = "decks";
    static final String PREVIEW_VIEW = "preview";
    static final String REVIEW_VIEW = "review";
    private static final int PREVIEW_FRONT = 0;
    private static final int PREVIEW_BACK = 1;
    private static final float CARD_TEXT_SIZE = 18f;
    /** Tall enough for about four lines of a card's text in a table. */
    private static final int CARD_ROW_HEIGHT = 72;

    private final CardLayout cards = new CardLayout();
    private final JPanel views = new JPanel(cards);
    private String currentView = DECKS_VIEW;

    private final DefaultTableModel deckModel = readOnlyModel("Deck", "Cards", "Due today");
    private final JTable deckTable = new StudyTable(deckModel);
    private final DefaultTableModel cardModel = readOnlyModel("Front", "Back", "Concept", "Next due");
    private final JButton generate = new JButton("Generate Deck");
    private final JButton reviewDue = new JButton("Review Due Cards");
    private final JButton reviewDeck = new JButton("Review Deck");
    private final JButton deleteDeck = new JButton("Delete Deck");
    private final JProgressBar progress = new JProgressBar();
    private final JLabel deckStatus = new JLabel(" ");
    private final JLabel dueLabel = new JLabel(" ");
    private final JLabel untaggedNote = new JLabel(" ");
    private final List<Long> deckIds = new ArrayList<>();
    private Map<Long, List<Flashcard>> decks = Map.of();
    private List<Concept> concepts = List.of();

    private final DefaultTableModel previewModel = new DefaultTableModel(
            new Object[] {"Front", "Back", "Concept"}, 0) {
        @Override
        public boolean isCellEditable(int row, int column) {
            return column == PREVIEW_FRONT || column == PREVIEW_BACK;
        }
    };
    private final JTable previewTable = new StudyTable(previewModel);
    private final JLabel previewStatus = new JLabel(" ");
    private final JButton saveDeck = new JButton("Save Deck");
    private final List<Flashcard> previewCards = new ArrayList<>();

    private final JLabel reviewProgress = new JLabel(" ");
    private final JTextArea reviewFront = wrappedText(" ");
    private final JTextArea reviewBack = wrappedText(" ");
    private final JButton showAnswer = new JButton("Show Answer");
    private final JPanel ratingButtons = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
    private List<Flashcard> reviewQueue = List.of();
    private int reviewIndex;
    private Supplier<List<Flashcard>> reviewScope = List::of;

    public FlashcardPanel(StudyAgentFacade facade) {
        super(facade);
        setLayout(new BorderLayout());
        views.add(buildDecksView(), DECKS_VIEW);
        views.add(buildPreviewView(), PREVIEW_VIEW);
        views.add(buildReviewView(), REVIEW_VIEW);
        add(views, BorderLayout.CENTER);
        updateDeckButtons();
    }

    @Override
    public String title() {
        return "Flashcards";
    }

    /** Selecting another course leaves a preview or review; the same course keeps the current view. */
    @Override
    public void setCourse(Course newCourse) {
        boolean sameCourse = course != null && newCourse != null && course.getId() == newCourse.getId();
        if (!sameCourse) {
            showView(DECKS_VIEW);
        }
        super.setCourse(newCourse);
    }

    /** Reloads the list of decks; it never disturbs a preview or a review in progress. */
    @Override
    public void refresh() {
        if (course == null) {
            fillDecks(List.of(), List.of());
            return;
        }
        long courseId = course.getId();
        record Loaded(List<Flashcard> cards, List<Concept> concepts) {
        }
        runInBackground(() -> new Loaded(facade.listFlashcards(courseId), facade.listConcepts(courseId)),
                loaded -> fillDecks(loaded.cards(), loaded.concepts()));
    }

    private static DefaultTableModel readOnlyModel(String... columns) {
        return new DefaultTableModel(columns, 0) {
            @Override
            public boolean isCellEditable(int row, int column) {
                return false;
            }
        };
    }

    // ----- decks -----

    private JPanel buildDecksView() {
        JPanel view = new JPanel(new BorderLayout(0, 6));
        view.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));
        JPanel top = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        generate.addActionListener(e -> openGenerateDialog());
        top.add(generate);
        reviewDue.addActionListener(e -> reviewDueCards());
        top.add(reviewDue);
        reviewDeck.addActionListener(e -> reviewSelectedDeck());
        top.add(reviewDeck);
        deleteDeck.addActionListener(e -> confirmDeleteDeck());
        top.add(deleteDeck);
        dueLabel.setFont(dueLabel.getFont().deriveFont(Font.BOLD));
        top.add(dueLabel);
        progress.setIndeterminate(true);
        progress.setVisible(false);
        top.add(progress);
        top.add(deckStatus);
        view.add(top, BorderLayout.NORTH);

        deckTable.getSelectionModel().addListSelectionListener(e -> showSelectedDeck());
        JTable cardTable = new StudyTable(cardModel);
        wrapCardText(cardTable);
        JPanel cardArea = new JPanel(new BorderLayout(0, 4));
        untaggedNote.setVisible(false);
        cardArea.add(untaggedNote, BorderLayout.NORTH);
        cardArea.add(new JScrollPane(cardTable), BorderLayout.CENTER);
        JSplitPane split = new JSplitPane(JSplitPane.VERTICAL_SPLIT, new JScrollPane(deckTable), cardArea);
        split.setResizeWeight(0.25);
        view.add(split, BorderLayout.CENTER);
        return view;
    }

    private void fillDecks(List<Flashcard> all, List<Concept> allConcepts) {
        concepts = allConcepts;
        Long selected = selectedDeckId();
        Map<Long, List<Flashcard>> grouped = new LinkedHashMap<>();
        all.forEach(card -> grouped.computeIfAbsent(card.getDeckId(), id -> new ArrayList<>()).add(card));
        decks = grouped;
        deckIds.clear();
        deckModel.setRowCount(0);
        LocalDate today = LocalDate.now();
        for (Map.Entry<Long, List<Flashcard>> deck : grouped.entrySet()) {
            deckIds.add(deck.getKey());
            long due = deck.getValue().stream().filter(card -> card.isDue(today)).count();
            deckModel.addRow(new Object[] {"#" + deck.getKey(), deck.getValue().size(), due});
        }
        int row = selected == null ? -1 : deckIds.indexOf(selected);
        if (row >= 0) {
            deckTable.setRowSelectionInterval(row, row);
        }
        showSelectedDeck();
        long dueToday = all.stream().filter(card -> card.isDue(today)).count();
        dueLabel.setText(course == null ? " " : dueToday + " card(s) due today");
        updateDeckButtons();
    }

    private Long selectedDeckId() {
        int row = deckTable.getSelectedRow();
        return row < 0 || row >= deckIds.size() ? null : deckIds.get(row);
    }

    private void showSelectedDeck() {
        cardModel.setRowCount(0);
        Long deckId = selectedDeckId();
        long untagged = 0;
        if (deckId != null) {
            for (Flashcard card : decks.getOrDefault(deckId, List.of())) {
                cardModel.addRow(new Object[] {card.getFront(), card.getBack(),
                        Concept.nameOf(card.getConceptId(), concepts), card.getNextDue()});
                if (card.getConceptId() == null) {
                    untagged++;
                }
            }
        }
        untaggedNote.setText(untagged == 1 ? "1 card has no concept, so reviewing it doesn't affect mastery."
                : untagged + " cards have no concept, so reviewing them doesn't affect mastery.");
        untaggedNote.setVisible(untagged > 0);
        updateDeckButtons();
    }

    private void updateDeckButtons() {
        generate.setEnabled(course != null && !progress.isVisible());
        reviewDue.setEnabled(course != null && !decks.isEmpty());
        reviewDeck.setEnabled(selectedDeckId() != null);
        deleteDeck.setEnabled(selectedDeckId() != null);
    }

    private void confirmDeleteDeck() {
        Long deckId = selectedDeckId();
        if (deckId != null && JOptionPane.showConfirmDialog(this, "Delete Deck #" + deckId + " with "
                + decks.get(deckId).size() + " card(s)? Mastery scores and history is kept.",
                "Delete Deck", JOptionPane.OK_CANCEL_OPTION) == JOptionPane.OK_OPTION) {
            deleteDeck(deckId);
        }
    }

    /** Runs once the deletion is confirmed; the change event refreshes the list. */
    void deleteDeck(long deckId) {
        runInBackground(() -> facade.deleteDeck(deckId));
    }

    // ----- generating -----

    private void openGenerateDialog() {
        long courseId = course.getId();
        record Scope(List<Material> materials, List<Concept> concepts) {
        }
        runInBackground(() -> new Scope(facade.listMaterials(courseId), facade.listConcepts(courseId)),
                scope -> askForDeckSettings(courseId, scope.materials(), scope.concepts()));
    }

    private void askForDeckSettings(long courseId, List<Material> materials, List<Concept> concepts) {
        JList<Material> materialList = multiSelectList(materials);
        JList<Concept> conceptList = multiSelectList(concepts);
        JSpinner count = new JSpinner(new SpinnerNumberModel(10, Flashcard.MIN_DECK_SIZE, Flashcard.MAX_DECK_SIZE, 1));

        JPanel scope = new JPanel(new GridLayout(1, 2, 8, 0));
        scope.add(titled("Materials", new JScrollPane(materialList)));
        scope.add(titled("Concepts", new JScrollPane(conceptList)));
        scope.setPreferredSize(new Dimension(480, 170));
        JPanel size = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        size.add(new JLabel("Number of cards:"));
        size.add(count);
        JPanel form = new JPanel(new BorderLayout(0, 8));
        form.add(new JLabel(SCOPE_HINT), BorderLayout.NORTH);
        form.add(scope, BorderLayout.CENTER);
        form.add(size, BorderLayout.SOUTH);

        if (JOptionPane.showConfirmDialog(this, form, "Generate Deck", JOptionPane.OK_CANCEL_OPTION,
                JOptionPane.PLAIN_MESSAGE) == JOptionPane.OK_OPTION) {
            generateCards(courseId, materialList.getSelectedValuesList().stream().map(Material::getId).toList(),
                    conceptList.getSelectedValuesList().stream().map(Concept::getId).toList(),
                    (Integer) count.getValue());
        }
    }

    private void generateCards(long courseId, List<Long> materialIds, List<Long> conceptIds, int count) {
        progress.setVisible(true);
        deckStatus.setText("Writing flashcards...");
        updateDeckButtons();
        record Generated(List<Flashcard> cards, List<Concept> concepts) {
        }
        runInBackground(() -> new Generated(facade.generateFlashcards(courseId, materialIds, conceptIds, count),
                facade.listConcepts(courseId)), generated -> {
            finishGenerating();
            if (course != null && course.getId() == courseId) {
                showPreview(generated.cards(), generated.concepts(), count);
            }
        }, error -> {
            finishGenerating();
            showError(error.getMessage());
        });
    }

    private void finishGenerating() {
        progress.setVisible(false);
        deckStatus.setText(" ");
        updateDeckButtons();
    }

    // ----- preview -----

    private JPanel buildPreviewView() {
        JPanel view = new JPanel(new BorderLayout(0, 6));
        view.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));
        JPanel top = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        saveDeck.addActionListener(e -> savePreview());
        top.add(saveDeck);
        JButton remove = new JButton("Remove");
        remove.addActionListener(e -> removeSelectedCard());
        top.add(remove);
        JButton cancel = new JButton("Cancel");
        cancel.addActionListener(e -> showView(DECKS_VIEW));
        top.add(cancel);
        top.add(previewStatus);
        view.add(top, BorderLayout.NORTH);
        wrapCardText(previewTable);
        view.add(new JScrollPane(previewTable), BorderLayout.CENTER);
        return view;
    }

    /** Shows unsaved cards so the student can edit or remove them before saving. */
    void showPreview(List<Flashcard> generated, List<Concept> courseConcepts, int requested) {
        previewCards.clear();
        previewCards.addAll(generated);
        previewModel.setRowCount(0);
        for (Flashcard card : generated) {
            previewModel.addRow(new Object[] {card.getFront(), card.getBack(),
                    Concept.nameOf(card.getConceptId(), courseConcepts)});
        }
        previewStatus.setText(generated.size() < requested
                ? "Only " + generated.size() + " of the " + requested + " requested cards were generated. The "
                        + "material may be too short, or some cards repeated existing ones."
                : "Edit or remove cards, then click Save Deck.");
        saveDeck.setEnabled(true);
        showView(PREVIEW_VIEW);
    }

    private void removeSelectedCard() {
        stopEditing();
        int row = previewTable.getSelectedRow();
        if (row >= 0) {
            previewCards.remove(row);
            previewModel.removeRow(row);
        }
    }

    /** Applies the student's edits and saves the remaining cards as a deck. */
    void savePreview() {
        stopEditing();
        if (previewCards.isEmpty()) {
            showError("There are no cards left to save.");
            return;
        }
        try {
            for (int row = 0; row < previewCards.size(); row++) {
                previewCards.get(row).setContent(String.valueOf(previewModel.getValueAt(row, PREVIEW_FRONT)),
                        String.valueOf(previewModel.getValueAt(row, PREVIEW_BACK)));
            }
        } catch (IllegalArgumentException e) {
            showError(e.getMessage());
            return;
        }
        List<Flashcard> toSave = List.copyOf(previewCards);
        saveDeck.setEnabled(false);
        runInBackground(() -> facade.saveFlashcards(toSave), saved -> showView(DECKS_VIEW), error -> {
            saveDeck.setEnabled(true);
            showError(error.getMessage());
        });
    }

    private void stopEditing() {
        if (previewTable.getCellEditor() != null) {
            previewTable.getCellEditor().stopCellEditing();
        }
    }

    /** Shows a card's full text in the first two columns, using taller rows. */
    private static void wrapCardText(JTable table) {
        table.setRowHeight(CARD_ROW_HEIGHT);
        table.getColumnModel().getColumn(0).setCellRenderer(new WrappedCell());
        table.getColumnModel().getColumn(1).setCellRenderer(new WrappedCell());
    }

    /** A table cell whose text wraps instead of being cut off. */
    private static final class WrappedCell extends JTextArea implements TableCellRenderer {
        WrappedCell() {
            setLineWrap(true);
            setWrapStyleWord(true);
        }

        @Override
        public Component getTableCellRendererComponent(JTable table, Object value, boolean selected, boolean focus,
                                                       int row, int column) {
            setText(value == null ? "" : value.toString());
            setFont(table.getFont());
            setBackground(selected ? table.getSelectionBackground() : table.getBackground());
            setForeground(selected ? table.getSelectionForeground() : table.getForeground());
            return this;
        }
    }

    /** Sets a preview cell as if the student had typed it; used by tests. */
    void editPreview(int row, String front, String back) {
        previewModel.setValueAt(front, row, PREVIEW_FRONT);
        previewModel.setValueAt(back, row, PREVIEW_BACK);
    }

    // ----- review -----

    private JPanel buildReviewView() {
        JPanel view = new JPanel(new BorderLayout(0, 12));
        view.setBorder(BorderFactory.createEmptyBorder(16, 16, 16, 16));
        JPanel top = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        JButton back = new JButton("Back to Decks");
        back.addActionListener(e -> showView(DECKS_VIEW));
        top.add(back);
        reviewProgress.setFont(reviewProgress.getFont().deriveFont(Font.BOLD));
        top.add(reviewProgress);
        view.add(top, BorderLayout.NORTH);

        reviewFront.setFont(Theme.textFont().deriveFont(Font.BOLD, CARD_TEXT_SIZE));
        reviewBack.setFont(Theme.textFont().deriveFont(CARD_TEXT_SIZE));
        JPanel card = new JPanel(new BorderLayout(0, 16));
        card.setBorder(BorderFactory.createEmptyBorder(16, 16, 16, 16));
        card.add(reviewFront, BorderLayout.NORTH);
        card.add(reviewBack, BorderLayout.CENTER);
        view.add(new JScrollPane(card), BorderLayout.CENTER);

        showAnswer.addActionListener(e -> revealAnswer());
        for (Rating rating : Rating.values()) {
            String name = rating.name();
            JButton button = new JButton(name.charAt(0) + name.substring(1).toLowerCase());
            button.addActionListener(e -> rate(rating));
            ratingButtons.add(button);
        }
        JPanel bottom = new JPanel();
        bottom.setLayout(new BoxLayout(bottom, BoxLayout.X_AXIS));
        bottom.add(showAnswer);
        bottom.add(ratingButtons);
        view.add(bottom, BorderLayout.SOUTH);
        return view;
    }

    void reviewDueCards() {
        long courseId = course.getId();
        startReview(() -> facade.getDueCards(courseId), () -> facade.listFlashcards(courseId));
    }

    private void reviewSelectedDeck() {
        long deckId = selectedDeckId();
        startReview(() -> facade.getDueCardsInDeck(deckId), () -> facade.getDeck(deckId));
    }

    /** @param scope the cards being reviewed, for saying when the next one is due */
    private void startReview(Supplier<List<Flashcard>> due, Supplier<List<Flashcard>> scope) {
        reviewScope = scope;
        runInBackground(due::get, cardsDue -> {
            reviewQueue = cardsDue;
            reviewIndex = 0;
            showView(REVIEW_VIEW);
            showCurrentCard();
        });
    }

    private void showCurrentCard() {
        if (reviewIndex >= reviewQueue.size()) {
            finishReview();
            return;
        }
        Flashcard card = reviewQueue.get(reviewIndex);
        reviewProgress.setText("Card " + (reviewIndex + 1) + " of " + reviewQueue.size());
        reviewFront.setText(card.getFront());
        reviewBack.setText(" ");
        showAnswer.setVisible(true);
        setRatingButtonsVisible(false);
    }

    /** Runs when Show Answer is clicked. */
    void revealAnswer() {
        Flashcard card = reviewQueue.get(reviewIndex);
        reviewBack.setText(card.getBack());
        showAnswer.setVisible(false);
        setRatingButtonsVisible(true);
    }

    /** Runs when a rating button is clicked. */
    void rate(Rating rating) {
        Flashcard card = reviewQueue.get(reviewIndex);
        setRatingButtonsVisible(false);
        runInBackground(() -> facade.reviewFlashcard(card.getId(), rating), reviewed -> {
            reviewIndex++;
            showCurrentCard();
        }, error -> {
            setRatingButtonsVisible(true);
            showError(error.getMessage());
        });
    }

    private void finishReview() {
        int reviewed = reviewQueue.size();
        reviewProgress.setText(reviewed == 0 ? "No cards are due" : "Review finished");
        reviewFront.setText(reviewed == 0 ? "There is nothing to review today." : "You reviewed " + reviewed
                + " card(s).");
        reviewBack.setText(" ");
        showAnswer.setVisible(false);
        setRatingButtonsVisible(false);
        runInBackground(() -> facade.getNextDueDate(reviewScope.get()), this::showNextDue);
    }

    private void showNextDue(Optional<LocalDate> next) {
        next.ifPresent(date -> reviewBack.setText("The next card is due on " + date + "."));
    }

    private void setRatingButtonsVisible(boolean visible) {
        ratingButtons.setVisible(visible);
    }

    // ----- shared -----

    private void showView(String view) {
        currentView = view;
        cards.show(views, view);
        if (DECKS_VIEW.equals(view)) {
            refresh();
        }
    }

    /** Which view is showing; used by tests. */
    String currentView() {
        return currentView;
    }

    /** Rows in the deck list; used by tests. */
    int deckCount() {
        return deckModel.getRowCount();
    }

    /** Selects a deck as if its row had been clicked; used by tests. */
    void selectDeck(int row) {
        deckTable.setRowSelectionInterval(row, row);
    }

    /** Clears the deck selection as a repeated click does; used by tests. */
    void clearDeckSelection() {
        deckTable.clearSelection();
    }

    /** A cell of the selected deck's card table; used by tests. */
    Object cardCell(int row, int column) {
        return cardModel.getValueAt(row, column);
    }

    /** The note about cards without a concept, or null when it is hidden; used by tests. */
    String untaggedNoteText() {
        return untaggedNote.isVisible() ? untaggedNote.getText() : null;
    }

    /** The due-today label; used by tests. */
    String dueText() {
        return dueLabel.getText();
    }

    /** The text on the front of the review card; used by tests. */
    String reviewFrontText() {
        return reviewFront.getText();
    }

    /** The text on the back of the review card; used by tests. */
    String reviewBackText() {
        return reviewBack.getText();
    }
}
