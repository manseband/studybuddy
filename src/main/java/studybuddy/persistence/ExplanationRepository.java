package studybuddy.persistence;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Optional;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import studybuddy.domain.Citation;
import studybuddy.domain.Explanation;

/** SQLite cache of explanations (F07), one per question of an attempt, so asking again costs no model call. */
public class ExplanationRepository {

    private final Database db;
    private final ObjectMapper mapper = new ObjectMapper();

    public ExplanationRepository(Database db) {
        this.db = db;
    }

    public void save(long attemptId, long questionId, Explanation explanation) {
        db.withConnection(c -> {
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT OR REPLACE INTO explanations(attempt_id, question_id, text, misconception, citation_json, "
                            + "source_available) VALUES (?, ?, ?, ?, ?, ?)")) {
                ps.setLong(1, attemptId);
                ps.setLong(2, questionId);
                ps.setString(3, explanation.text());
                ps.setString(4, explanation.misconception());
                ps.setString(5, toJson(explanation.citation()));
                ps.setInt(6, explanation.sourceAvailable() ? 1 : 0);
                return ps.executeUpdate();
            }
        });
    }

    public Optional<Explanation> find(long attemptId, long questionId) {
        return db.withConnection(c -> {
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT * FROM explanations WHERE attempt_id = ? AND question_id = ?")) {
                ps.setLong(1, attemptId);
                ps.setLong(2, questionId);
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) {
                        return Optional.empty();
                    }
                    return Optional.of(new Explanation(rs.getString("text"), rs.getString("misconception"),
                            fromJson(rs.getString("citation_json")), rs.getInt("source_available") == 1));
                }
            }
        });
    }

    public void delete(long attemptId, long questionId) {
        db.withConnection(c -> {
            try (PreparedStatement ps = c.prepareStatement(
                    "DELETE FROM explanations WHERE attempt_id = ? AND question_id = ?")) {
                ps.setLong(1, attemptId);
                ps.setLong(2, questionId);
                return ps.executeUpdate();
            }
        });
    }

    private String toJson(Citation citation) throws SQLException {
        if (citation == null) {
            return null;
        }
        try {
            return mapper.writeValueAsString(citation);
        } catch (JsonProcessingException e) {
            throw new SQLException("Could not store the citation as JSON", e);
        }
    }

    private Citation fromJson(String json) throws SQLException {
        if (json == null) {
            return null;
        }
        try {
            return mapper.readValue(json, Citation.class);
        } catch (JsonProcessingException e) {
            throw new SQLException("Stored citation is not valid JSON", e);
        }
    }
}
