package studybuddy.persistence;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import studybuddy.domain.Answer;
import studybuddy.domain.QaEntry;

/** SQLite storage for the Q&A history (F04). The answer and its citations are stored as JSON. */
public class QaHistoryRepository implements Repository<QaEntry> {

    private final Database db;
    private final ObjectMapper mapper = new ObjectMapper();

    public QaHistoryRepository(Database db) {
        this.db = db;
    }

    /** History entries are never edited, so save always inserts a new entry. */
    @Override
    public QaEntry save(QaEntry entry) {
        if (entry.id() != 0) {
            throw new IllegalArgumentException("Q&A history entries cannot be changed");
        }
        return db.withConnection(c -> {
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO qa_history(course_id, question, answer_json, asked_at) VALUES (?, ?, ?, ?)",
                    Statement.RETURN_GENERATED_KEYS)) {
                ps.setLong(1, entry.courseId());
                ps.setString(2, entry.question());
                ps.setString(3, toJson(entry.answer()));
                ps.setString(4, entry.askedAt().toString());
                ps.executeUpdate();
                return entry.withId(SqlUtil.generatedId(ps));
            }
        });
    }

    @Override
    public Optional<QaEntry> findById(long id) {
        List<QaEntry> found = query("SELECT * FROM qa_history WHERE id = ?", id);
        return found.stream().findFirst();
    }

    /** Most recent first. */
    @Override
    public List<QaEntry> findByCourse(long courseId) {
        return query("SELECT * FROM qa_history WHERE course_id = ? ORDER BY asked_at DESC, id DESC", courseId);
    }

    @Override
    public void delete(long id) {
        db.withConnection(c -> {
            try (PreparedStatement ps = c.prepareStatement("DELETE FROM qa_history WHERE id = ?")) {
                ps.setLong(1, id);
                return ps.executeUpdate();
            }
        });
    }

    private List<QaEntry> query(String sql, long param) {
        return db.withConnection(c -> {
            List<QaEntry> entries = new ArrayList<>();
            try (PreparedStatement ps = c.prepareStatement(sql)) {
                ps.setLong(1, param);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        entries.add(new QaEntry(rs.getLong("id"), rs.getLong("course_id"), rs.getString("question"),
                                fromJson(rs.getString("answer_json")), LocalDateTime.parse(rs.getString("asked_at"))));
                    }
                }
            }
            return entries;
        });
    }

    private String toJson(Answer answer) throws SQLException {
        try {
            return mapper.writeValueAsString(answer);
        } catch (JsonProcessingException e) {
            throw new SQLException("Could not store the answer as JSON", e);
        }
    }

    private Answer fromJson(String json) throws SQLException {
        try {
            return mapper.readValue(json, Answer.class);
        } catch (JsonProcessingException e) {
            throw new SQLException("Stored answer is not valid JSON", e);
        }
    }
}
