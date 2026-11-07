package studybuddy.presentation.gui;

import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JTable;
import javax.swing.JTextArea;
import javax.swing.table.DefaultTableModel;

import studybuddy.application.StudyAgentFacade;
import studybuddy.domain.Concept;
import studybuddy.domain.Material;

/**
 * F03: extract concepts from a material, review them (edit, remove) and save; manage saved concepts
 * (edit, merge, delete). Reviewing unsaved candidates happens in the same table as the saved concepts.
 */
public class ConceptsPanel extends FeaturePanel {

    private static final int NAME = 0;
    private static final int DEFINITION = 1;
    private static final int PAGES = 2;
    private static final int IMPORTANCE = 3;

    private final DefaultTableModel model = new DefaultTableModel(
            new Object[] {"Name", "Definition", "Pages", "Importance (1-5)"}, 0) {
        @Override
        public boolean isCellEditable(int row, int column) {
            return column != PAGES;
        }
    };
    private final JTable table = new StudyTable(model);
    private final JComboBox<Material> materialSelector = new JComboBox<>();
    /** Shown instead of the selector when there are no materials; an empty dropdown looks like a blank choice. */
    private final JLabel noMaterials = new JLabel("No materials yet");
    private final JButton extract = new JButton("Extract Concepts");
    private final JButton save = new JButton("Save All");
    private final JButton remove = new JButton("Remove");
    private final JButton merge = new JButton("Merge Into...");
    private final JLabel status = new JLabel(" ");
    private final JTextArea details = new JTextArea(7, 40);
    private List<Concept> concepts = new ArrayList<>();
    /** The material being reviewed; null when the table shows saved concepts. */
    private Material reviewing;
    private boolean filling;

    public ConceptsPanel(StudyAgentFacade facade) {
        super(facade);
        setLayout(new BorderLayout(0, 6));
        setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));

        JPanel top = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        top.add(new JLabel("Material:"));
        top.add(materialSelector);
        top.add(noMaterials);
        extract.addActionListener(e -> extractConcepts());
        top.add(extract);
        save.addActionListener(e -> saveCandidates());
        top.add(save);
        remove.addActionListener(e -> removeSelected());
        top.add(remove);
        merge.addActionListener(e -> mergeSelected());
        top.add(merge);
        JPanel north = new JPanel(new BorderLayout(0, 4));
        north.add(top, BorderLayout.NORTH);
        north.add(status, BorderLayout.SOUTH);
        add(north, BorderLayout.NORTH);

        model.addTableModelListener(e -> {
            if (!filling && e.getFirstRow() >= 0 && e.getColumn() >= 0) {
                applyEdit(e.getFirstRow());
            }
        });
        table.getSelectionModel().addListSelectionListener(e -> {
            showDetails();
            updateButtons();
        });
        details.setEditable(false);
        details.setLineWrap(true);
        details.setWrapStyleWord(true);
        JSplitPane split = new JSplitPane(JSplitPane.VERTICAL_SPLIT, new JScrollPane(table), new JScrollPane(details));
        split.setResizeWeight(0.75);
        add(split, BorderLayout.CENTER);
        updateButtons();
    }

    @Override
    public String title() {
        return "Concepts";
    }

    /** Switching course cancels a review in progress. */
    @Override
    public void setCourse(studybuddy.domain.Course newCourse) {
        reviewing = null;
        status.setText(" ");
        materialSelector.removeAllItems();
        super.setCourse(newCourse);
    }

    @Override
    public void refresh() {
        if (course == null) {
            reviewing = null;
            fill(new ArrayList<>());
            materialSelector.removeAllItems();
            updateButtons();
            return;
        }
        long courseId = course.getId();
        record Loaded(List<Material> materials, List<Concept> concepts) {
        }
        runInBackground(() -> new Loaded(facade.listMaterials(courseId), facade.listConcepts(courseId)), loaded -> {
            Material selected = (Material) materialSelector.getSelectedItem();
            materialSelector.removeAllItems();
            for (Material m : loaded.materials()) {
                materialSelector.addItem(m);
                if (selected != null && selected.getId() == m.getId()) {
                    materialSelector.setSelectedItem(m);
                }
            }
            if (reviewing == null) {
                fill(new ArrayList<>(loaded.concepts()));
            }
            updateButtons();
        });
    }

    /** Whether the material dropdown is showing rather than the "No materials yet" text; used by tests. */
    boolean materialSelectorShown() {
        return materialSelector.isVisible() && !noMaterials.isVisible();
    }

    /** Rows currently shown; used by tests. */
    int rowCount() {
        return model.getRowCount();
    }

    private void fill(List<Concept> list) {
        concepts = list;
        filling = true;
        model.setRowCount(0);
        for (Concept c : list) {
            model.addRow(new Object[] {c.getName(), c.getDefinition(), pages(c), c.getImportance()});
        }
        filling = false;
        details.setText("");
    }

    private void updateButtons() {
        boolean hasMaterials = materialSelector.getItemCount() > 0;
        materialSelector.setVisible(hasMaterials);
        noMaterials.setVisible(!hasMaterials);
        extract.setEnabled(course != null && hasMaterials);
        save.setEnabled(reviewing != null);
        remove.setEnabled(selected() != null);
        merge.setEnabled(reviewing == null && selected() != null);
    }

    private void extractConcepts() {
        Material material = (Material) materialSelector.getSelectedItem();
        if (material == null) {
            return;
        }
        extract.setEnabled(false);
        status.setText("Extracting concepts...");
        runInBackground(() -> facade.extractConcepts(material.getId()), candidates -> {
            status.setText(" ");
            if (candidates.isEmpty()) {
                updateButtons();
                showInfo("No concepts were found. The material may be too short.");
                return;
            }
            reviewing = material;
            fill(new ArrayList<>(candidates));
            status.setText("Remove the concepts you don't want. Save All keeps every concept left in the list.");
            updateButtons();
        }, error -> {
            status.setText(" ");
            updateButtons();
            showError(error.getMessage());
        });
    }

    private void saveCandidates() {
        Material material = reviewing;
        List<Concept> toSave = List.copyOf(concepts);
        reviewing = null;
        status.setText(" ");
        runInBackground(() -> facade.saveConcepts(material.getId(), toSave), saved -> refresh());
    }

    /** A cell edit changes the candidate object while reviewing, or saves straight away otherwise. */
    private void applyEdit(int row) {
        if (row >= concepts.size()) {
            return;
        }
        Concept concept = concepts.get(row);
        try {
            String name = String.valueOf(model.getValueAt(row, NAME));
            String definition = String.valueOf(model.getValueAt(row, DEFINITION));
            int importance = Integer.parseInt(String.valueOf(model.getValueAt(row, IMPORTANCE)).strip());
            if (reviewing != null) {
                concept.setName(name);
                concept.setDefinition(definition);
                concept.setImportance(importance);
            } else {
                facade.updateConcept(concept.getId(), name, definition, importance);
            }
        } catch (NumberFormatException e) {
            showError("Importance must be a number from 1 to 5.");
            refresh();
        } catch (IllegalArgumentException e) {
            showError(e.getMessage());
            refresh();
        }
    }

    private Concept selected() {
        int row = table.getSelectedRow();
        return row < 0 || row >= concepts.size() ? null : concepts.get(row);
    }

    private void removeSelected() {
        Concept concept = selected();
        if (concept == null) {
            return;
        }
        if (reviewing != null) {
            concepts.remove(concept);
            fill(concepts);
        } else if (JOptionPane.showConfirmDialog(this, "Delete " + concept.getName()
                + "? Questions and flashcards tagged with it become untagged.", "Delete concept",
                JOptionPane.OK_CANCEL_OPTION) == JOptionPane.OK_OPTION) {
            runInBackground(() -> facade.deleteConcept(concept.getId()));
        }
    }

    private void mergeSelected() {
        Concept from = selected();
        if (from == null) {
            return;
        }
        Object[] others = concepts.stream().filter(c -> c != from).toArray();
        Object into = JOptionPane.showInputDialog(this, "Merge \"" + from.getName() + "\" into:", "Merge concept",
                JOptionPane.PLAIN_MESSAGE, null, others, others.length == 0 ? null : others[0]);
        if (into instanceof Concept target) {
            runInBackground(() -> facade.mergeConcepts(from.getId(), target.getId()), merged -> { });
        }
    }

    private void showDetails() {
        Concept concept = selected();
        if (concept == null) {
            details.setText("");
            return;
        }
        String prerequisites = concept.getPrerequisites().stream().map(Concept::getName)
                .collect(Collectors.joining(", "));
        String text = concept.getName() + "\n" + concept.getDefinition() + "\n\nPrerequisites: "
                + (prerequisites.isEmpty() ? "none" : prerequisites) + "\nPages: " + pages(concept);
        if (concept.getId() == 0) {
            setDetails(text);
            return;
        }
        long id = concept.getId();
        runInBackground(() -> facade.getConceptMaterials(id), materials -> setDetails(text + "\nAppears in: "
                + materials.stream().map(Material::getTitle).collect(Collectors.joining(", "))));
    }

    /** Shows the text from its first line; a text area otherwise scrolls to the end. */
    private void setDetails(String text) {
        details.setText(text);
        details.setCaretPosition(0);
    }

    private static String pages(Concept concept) {
        return concept.getSourcePages().stream().map(String::valueOf).collect(Collectors.joining(", "));
    }
}
