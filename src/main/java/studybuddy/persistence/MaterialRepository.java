package studybuddy.persistence;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import studybuddy.domain.Chunk;
import studybuddy.domain.Material;
import studybuddy.domain.MaterialStatus;

/**
 * SQLite storage for materials and their chunks. A new material and all of its chunks are inserted
 * in one transaction, so a failure leaves nothing half-saved (F01).
 */
public class MaterialRepository implements Repository<Material> {

    private static final String MATERIAL_COLUMNS =
            "id, course_id, title, file_type, status, content_hash, page_count, word_count, added_at";
    private static final String CHUNK_COLUMNS =
            "id, material_id, course_id, position, text, page_number, end_page, embedding, embedding_model";

    private final Database db;

    public MaterialRepository(Database db) {
        this.db = db;
    }

    /** Inserts a new material with its chunks, or updates title and status of an existing one. */
    @Override
    public Material save(Material material) {
        return db.inTransaction(c -> {
            if (material.getId() == 0) {
                insert(c, material);
            } else {
                try (PreparedStatement ps = c.prepareStatement(
                        "UPDATE materials SET title = ?, status = ? WHERE id = ?")) {
                    ps.setString(1, material.getTitle());
                    ps.setString(2, material.getStatus().name());
                    ps.setLong(3, material.getId());
                    ps.executeUpdate();
                }
            }
            return material;
        });
    }

    @Override
    public Optional<Material> findById(long id) {
        return db.withConnection(c -> {
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT " + MATERIAL_COLUMNS + " FROM materials WHERE id = ?")) {
                ps.setLong(1, id);
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) {
                        return Optional.empty();
                    }
                    Material material = map(rs);
                    material.setChunks(chunksWhere(c, "material_id", id));
                    return Optional.of(material);
                }
            }
        });
    }

    @Override
    public List<Material> findByCourse(long courseId) {
        return db.withConnection(c -> {
            List<Material> materials = new ArrayList<>();
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT " + MATERIAL_COLUMNS + " FROM materials WHERE course_id = ? ORDER BY added_at, id")) {
                ps.setLong(1, courseId);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        materials.add(map(rs));
                    }
                }
            }
            for (Material material : materials) {
                material.setChunks(chunksWhere(c, "material_id", material.getId()));
            }
            return materials;
        });
    }

    /** Material in the course with the same file hash, used to detect duplicate uploads. */
    public Optional<Material> findByHash(long courseId, String contentHash) {
        Optional<Long> id = db.withConnection(c -> {
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT id FROM materials WHERE course_id = ? AND content_hash = ?")) {
                ps.setLong(1, courseId);
                ps.setString(2, contentHash);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? Optional.of(rs.getLong(1)) : Optional.empty();
                }
            }
        });
        return id.flatMap(this::findById);
    }

    /** Every chunk of the course, in material and position order. Used to build the search index. */
    public List<Chunk> findChunksByCourse(long courseId) {
        return db.withConnection(c -> chunksWhere(c, "course_id", courseId));
    }

    /** Stores newly computed embeddings (with their model name) for existing chunks. */
    public void updateEmbeddings(List<Chunk> chunks) {
        db.inTransaction(c -> {
            try (PreparedStatement ps = c.prepareStatement(
                    "UPDATE chunks SET embedding = ?, embedding_model = ? WHERE id = ?")) {
                for (Chunk chunk : chunks) {
                    ps.setBytes(1, SqlUtil.toBytes(chunk.getEmbedding()));
                    ps.setString(2, chunk.getEmbeddingModel());
                    ps.setLong(3, chunk.getId());
                    ps.addBatch();
                }
                ps.executeBatch();
            }
            return null;
        });
    }

    @Override
    public void delete(long id) {
        db.withConnection(c -> {
            try (PreparedStatement ps = c.prepareStatement("DELETE FROM materials WHERE id = ?")) {
                ps.setLong(1, id);
                return ps.executeUpdate();
            }
        });
    }

    private void insert(Connection c, Material material) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "INSERT INTO materials(course_id, title, file_type, status, content_hash, page_count, word_count, added_at)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?, ?)", Statement.RETURN_GENERATED_KEYS)) {
            ps.setLong(1, material.getCourseId());
            ps.setString(2, material.getTitle());
            ps.setString(3, material.getFileType());
            ps.setString(4, material.getStatus().name());
            ps.setString(5, material.getContentHash());
            ps.setInt(6, material.getPageCount());
            ps.setInt(7, material.getWordCount());
            ps.setString(8, material.getAddedAt().toString());
            ps.executeUpdate();
            material.setId(SqlUtil.generatedId(ps));
        }
        try (PreparedStatement ps = c.prepareStatement(
                "INSERT INTO chunks(material_id, course_id, position, text, page_number, end_page, embedding,"
                        + " embedding_model) VALUES (?, ?, ?, ?, ?, ?, ?, ?)", Statement.RETURN_GENERATED_KEYS)) {
            for (Chunk chunk : material.getChunks()) {
                chunk.setCourseId(material.getCourseId());
                ps.setLong(1, material.getId());
                ps.setLong(2, material.getCourseId());
                ps.setInt(3, chunk.getPosition());
                ps.setString(4, chunk.getText());
                ps.setInt(5, chunk.getPageNumber());
                ps.setInt(6, chunk.getEndPage());
                ps.setBytes(7, SqlUtil.toBytes(chunk.getEmbedding()));
                ps.setString(8, chunk.getEmbeddingModel());
                ps.executeUpdate();
                chunk.setId(SqlUtil.generatedId(ps));
            }
        }
    }

    private static List<Chunk> chunksWhere(Connection c, String column, long value) throws SQLException {
        List<Chunk> chunks = new ArrayList<>();
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT " + CHUNK_COLUMNS + " FROM chunks WHERE " + column + " = ? ORDER BY material_id, position")) {
            ps.setLong(1, value);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    Chunk chunk = new Chunk(rs.getLong("id"), rs.getLong("material_id"), rs.getLong("course_id"),
                            rs.getInt("position"), rs.getString("text"), rs.getInt("page_number"),
                            rs.getInt("end_page"));
                    chunk.setEmbedding(SqlUtil.toFloats(rs.getBytes("embedding")), rs.getString("embedding_model"));
                    chunks.add(chunk);
                }
            }
        }
        return chunks;
    }

    private static Material map(ResultSet rs) throws SQLException {
        return new Material(rs.getLong("id"), rs.getLong("course_id"), rs.getString("title"),
                rs.getString("file_type"), MaterialStatus.valueOf(rs.getString("status")),
                rs.getString("content_hash"), rs.getInt("page_count"), rs.getInt("word_count"),
                LocalDateTime.parse(rs.getString("added_at")));
    }
}
