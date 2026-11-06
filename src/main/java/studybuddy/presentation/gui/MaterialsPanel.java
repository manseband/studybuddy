package studybuddy.presentation.gui;

import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.io.File;
import java.util.List;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JFileChooser;
import javax.swing.JLabel;
import javax.swing.JMenuItem;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JProgressBar;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.filechooser.FileNameExtensionFilter;
import javax.swing.table.DefaultTableModel;

import studybuddy.application.DuplicateMaterialException;
import studybuddy.application.StudyAgentFacade;
import studybuddy.domain.Material;

/** F01: upload materials to the selected course; right-click a row to rename or remove it. */
public class MaterialsPanel extends FeaturePanel {

    private final DefaultTableModel model = new DefaultTableModel(
            new Object[] {"Title", "Type", "Pages", "Words", "Chunks", "Status"}, 0) {
        @Override
        public boolean isCellEditable(int row, int column) {
            return false;
        }
    };
    private final JTable table = new StudyTable(model);
    private final JButton upload = new JButton("Upload Material");
    private final JProgressBar progress = new JProgressBar();
    private final JLabel summary = new JLabel(" ");
    private List<Material> materials = List.of();

    public MaterialsPanel(StudyAgentFacade facade) {
        super(facade);
        setLayout(new BorderLayout(0, 6));
        setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));

        JPanel top = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        upload.addActionListener(e -> chooseFile());
        top.add(upload);
        progress.setIndeterminate(true);
        progress.setVisible(false);
        top.add(progress);
        add(top, BorderLayout.NORTH);

        add(new JScrollPane(table), BorderLayout.CENTER);
        add(summary, BorderLayout.SOUTH);

        JPopupMenu menu = new JPopupMenu();
        JMenuItem rename = new JMenuItem("Rename");
        rename.addActionListener(e -> renameSelected());
        menu.add(rename);
        JMenuItem remove = new JMenuItem("Remove");
        remove.addActionListener(e -> removeSelected());
        menu.add(remove);
        table.addMouseListener(new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent e) {
                showMenu(e);
            }

            @Override
            public void mouseReleased(MouseEvent e) {
                showMenu(e);
            }

            private void showMenu(MouseEvent e) {
                int row = table.rowAtPoint(e.getPoint());
                if (e.isPopupTrigger() && row >= 0) {
                    table.setRowSelectionInterval(row, row);
                    menu.show(table, e.getX(), e.getY());
                }
            }
        });
    }

    @Override
    public String title() {
        return "Materials";
    }

    @Override
    public void refresh() {
        upload.setEnabled(course != null);
        if (course == null) {
            show(List.of());
            return;
        }
        long courseId = course.getId();
        runInBackground(() -> facade.listMaterials(courseId), this::show);
    }

    /** Rows currently shown; used by tests. */
    int rowCount() {
        return model.getRowCount();
    }

    private void show(List<Material> list) {
        materials = list;
        model.setRowCount(0);
        for (Material m : list) {
            model.addRow(new Object[] {m.getTitle(), m.getFileType(), m.getPageCount(), m.getWordCount(),
                    m.getChunkCount(), m.getStatus().label()});
        }
    }

    private void chooseFile() {
        JFileChooser chooser = new JFileChooser();
        chooser.setFileFilter(new FileNameExtensionFilter("PDF, TXT or Markdown", "pdf", "txt", "md"));
        if (chooser.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
            upload(chooser.getSelectedFile(), false);
        }
    }

    private void upload(File file, boolean replace) {
        long courseId = course.getId();
        upload.setEnabled(false);
        progress.setVisible(true);
        summary.setText("Reading " + file.getName() + "...");
        runInBackground(() -> facade.uploadMaterial(courseId, file.toPath(), replace), material -> {
            finishUpload();
            summary.setText("Added " + material.getTitle() + ": " + material.getPageCount() + " page(s), "
                    + material.getWordCount() + " words, " + material.getChunkCount() + " chunks.");
            if (material.getChunkCount() == 0) {
                showInfo("No text was found in this file. Scanned PDFs need OCR, which is not supported.");
            }
        }, error -> {
            finishUpload();
            summary.setText(" ");
            if (error instanceof DuplicateMaterialException duplicate) {
                askReplaceOrSkip(file, duplicate);
            } else {
                showError(error.getMessage());
            }
        });
    }

    private void finishUpload() {
        progress.setVisible(false);
        upload.setEnabled(course != null);
    }

    private void askReplaceOrSkip(File file, DuplicateMaterialException duplicate) {
        Object[] options = {"Replace", "Skip"};
        int choice = JOptionPane.showOptionDialog(this, duplicate.getMessage() + ".", "Duplicate file",
                JOptionPane.DEFAULT_OPTION, JOptionPane.QUESTION_MESSAGE, null, options, options[1]);
        if (choice == 0) {
            upload(file, true);
        }
    }

    private Material selected() {
        int row = table.getSelectedRow();
        return row < 0 || row >= materials.size() ? null : materials.get(row);
    }

    private void renameSelected() {
        Material material = selected();
        if (material == null) {
            return;
        }
        String title = JOptionPane.showInputDialog(this, "New title:", material.getTitle());
        if (title != null && !title.isBlank()) {
            runInBackground(() -> facade.renameMaterial(material.getId(), title), renamed -> { });
        }
    }

    private void removeSelected() {
        Material material = selected();
        if (material != null && JOptionPane.showConfirmDialog(this, "Remove " + material.getTitle() + "?", "Remove",
                JOptionPane.OK_CANCEL_OPTION) == JOptionPane.OK_OPTION) {
            runInBackground(() -> facade.removeMaterial(material.getId()));
        }
    }
}
