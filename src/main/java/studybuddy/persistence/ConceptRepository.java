package studybuddy.persistence;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import studybuddy.domain.Concept;

/**
 * SQLite storage for course concepts (F03), their prerequisite links and the materials they appear in.
 * Deleting a concept leaves its questions and flashcards untagged (ON DELETE SET NULL in the schema).
 */
public class ConceptRepository implements Repository<Concept> {

    private final Database db;
    private final ObjectMapper mapper = new ObjectMapper();

    public ConceptRepository(Database db) {
        this.db = db;
    }

    /**
     * Inserts or updates the concept and replaces its prerequisite links. Prerequisites that are not
     * saved yet (id 0) are skipped; save them first and save this concept again to link them.
     */
    @Override
    public Concept save(Concept concept) {
        return db.inTransaction(c -> {
            if (concept.getId() == 0) {
                try (PreparedStatement ps = c.prepareStatement(
                        "INSERT INTO concepts(course_id, name, definition, importance, source_pages) VALUES (?, ?, ?, ?, ?)",
                        Statement.RETURN_GENERATED_KEYS)) {
                    ps.setLong(1, concept.getCourseId());
                    bindFields(ps, 2, concept);
                    ps.executeUpdate();
                    concept.setId(SqlUtil.generatedId(ps));
                }
            } else {
                try (PreparedStatement ps = c.prepareStatement(
                        "UPDATE concepts SET name = ?, definition = ?, importance = ?, source_pages = ? WHERE id = ?")) {
                    bindFields(ps, 1, concept);
                    ps.setLong(5, concept.getId());
                    ps.executeUpdate();
                }
            }
            try (PreparedStatement del = c.prepareStatement("DELETE FROM concept_prerequisites WHERE concept_id = ?")) {
                del.setLong(1, concept.getId());
                del.executeUpdate();
            }
            try (PreparedStatement ins = c.prepareStatement(
                    "INSERT OR IGNORE INTO concept_prerequisites(concept_id, prerequisite_id) VALUES (?, ?)")) {
                for (Concept prerequisite : concept.getPrerequisites()) {
                    if (prerequisite.getId() != 0) {
                        ins.setLong(1, concept.getId());
                        ins.setLong(2, prerequisite.getId());
                        ins.executeUpdate();
                    }
                }
            }
            return concept;
        });
    }

    /** The concept with its prerequisites (loaded without their own prerequisites). */
    @Override
    public Optional<Concept> findById(long id) {
        return db.withConnection(c -> {
            List<Concept> found = load(c, "SELECT * FROM concepts WHERE id = ?", id);
            if (found.isEmpty()) {
                return Optional.empty();
            }
            Concept concept = found.get(0);
            for (long prerequisiteId : prerequisiteIds(c, id)) {
                load(c, "SELECT * FROM concepts WHERE id = ?", prerequisiteId).forEach(concept::addPrerequisite);
            }
            return Optional.of(concept);
        });
    }

    /** All concepts of the course, sorted by name, with prerequisite links between them. */
    @Override
    public List<Concept> findByCourse(long courseId) {
        return db.withConnection(c -> {
            Map<Long, Concept> byId = new LinkedHashMap<>();
            for (Concept concept : load(c, "SELECT * FROM concepts WHERE course_id = ? ORDER BY name COLLATE NOCASE",
                    courseId)) {
                byId.put(concept.getId(), concept);
            }
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT p.concept_id, p.prerequisite_id FROM concept_prerequisites p "
                            + "JOIN concepts k ON k.id = p.concept_id WHERE k.course_id = ?")) {
                ps.setLong(1, courseId);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        Concept concept = byId.get(rs.getLong(1));
                        Concept prerequisite = byId.get(rs.getLong(2));
                        if (concept != null && prerequisite != null) {
                            concept.addPrerequisite(prerequisite);
                        }
                    }
                }
            }
            return new ArrayList<>(byId.values());
        });
    }

    @Override
    public void delete(long id) {
        db.withConnection(c -> {
            try (PreparedStatement ps = c.prepareStatement("DELETE FROM concepts WHERE id = ?")) {
                ps.setLong(1, id);
                return ps.executeUpdate();
            }
        });
    }

    /** Records that the concept appears in the material. */
    public void linkMaterial(long conceptId, long materialId) {
        db.withConnection(c -> {
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT OR IGNORE INTO concept_materials(concept_id, material_id) VALUES (?, ?)")) {
                ps.setLong(1, conceptId);
                ps.setLong(2, materialId);
                return ps.executeUpdate();
            }
        });
    }

    /** Ids of the materials the concept appears in. */
    public List<Long> findMaterialIds(long conceptId) {
        return db.withConnection(c -> {
            List<Long> ids = new ArrayList<>();
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT material_id FROM concept_materials WHERE concept_id = ? ORDER BY material_id")) {
                ps.setLong(1, conceptId);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        ids.add(rs.getLong(1));
                    }
                }
            }
            return ids;
        });
    }

    /**
     * Merges concept fromId into intoId: questions, flashcards, review evidence, material links and
     * prerequisite links move to intoId, then fromId is deleted. Runs as one transaction.
     */
    public void merge(long fromId, long intoId) {
        if (fromId == intoId) {
            throw new IllegalArgumentException("A concept cannot be merged into itself");
        }
        db.inTransaction(c -> {
            for (String table : List.of("questions", "flashcards", "flashcard_reviews")) {
                update(c, "UPDATE " + table + " SET concept_id = ? WHERE concept_id = ?", intoId, fromId);
            }
            update(c, "INSERT OR IGNORE INTO concept_materials(concept_id, material_id) "
                    + "SELECT ?, material_id FROM concept_materials WHERE concept_id = ?", intoId, fromId);
            update(c, "INSERT OR IGNORE INTO concept_prerequisites(concept_id, prerequisite_id) "
                    + "SELECT ?, prerequisite_id FROM concept_prerequisites WHERE concept_id = ? AND prerequisite_id <> ?",
                    intoId, fromId, intoId);
            update(c, "INSERT OR IGNORE INTO concept_prerequisites(concept_id, prerequisite_id) "
                    + "SELECT concept_id, ? FROM concept_prerequisites WHERE prerequisite_id = ? AND concept_id <> ?",
                    intoId, fromId, intoId);
            update(c, "DELETE FROM concepts WHERE id = ?", fromId);
            return null;
        });
    }

    private static void update(Connection c, String sql, long... params) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            for (int i = 0; i < params.length; i++) {
                ps.setLong(i + 1, params[i]);
            }
            ps.executeUpdate();
        }
    }

    private void bindFields(PreparedStatement ps, int first, Concept concept) throws SQLException {
        ps.setString(first, concept.getName());
        ps.setString(first + 1, concept.getDefinition());
        ps.setInt(first + 2, concept.getImportance());
        try {
            ps.setString(first + 3, mapper.writeValueAsString(concept.getSourcePages()));
        } catch (JsonProcessingException e) {
            throw new SQLException("Could not store source pages", e);
        }
    }

    private List<Concept> load(Connection c, String sql, long param) throws SQLException {
        List<Concept> concepts = new ArrayList<>();
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setLong(1, param);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    Concept concept = new Concept(rs.getLong("id"), rs.getLong("course_id"), rs.getString("name"),
                            rs.getString("definition"), rs.getInt("importance"));
                    try {
                        concept.addSourcePages(mapper.readValue(rs.getString("source_pages"),
                                new TypeReference<List<Integer>>() { }));
                    } catch (JsonProcessingException e) {
                        throw new SQLException("Stored source pages are not valid JSON", e);
                    }
                    concepts.add(concept);
                }
            }
        }
        return concepts;
    }

    private static List<Long> prerequisiteIds(Connection c, long conceptId) throws SQLException {
        List<Long> ids = new ArrayList<>();
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT prerequisite_id FROM concept_prerequisites WHERE concept_id = ?")) {
            ps.setLong(1, conceptId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    ids.add(rs.getLong(1));
                }
            }
        }
        return ids;
    }
}
