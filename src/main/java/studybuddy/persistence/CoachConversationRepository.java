package studybuddy.persistence;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/** SQLite storage for Study Coach conversations (F10): one line each for the study history, never the full text. */
public class CoachConversationRepository {

    /** The start of a conversation's first message, and when it was sent. */
    public record Conversation(String firstMessage, LocalDateTime startedAt) {
    }

    private final Database db;

    public CoachConversationRepository(Database db) {
        this.db = db;
    }

    public void add(long courseId, Conversation conversation) {
        db.withConnection(c -> {
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO coach_conversations(course_id, first_message, started_at) VALUES (?, ?, ?)")) {
                ps.setLong(1, courseId);
                ps.setString(2, conversation.firstMessage());
                ps.setString(3, conversation.startedAt().toString());
                return ps.executeUpdate();
            }
        });
    }

    /** Oldest first. */
    public List<Conversation> findByCourse(long courseId) {
        return db.withConnection(c -> {
            List<Conversation> conversations = new ArrayList<>();
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT * FROM coach_conversations WHERE course_id = ? ORDER BY started_at, id")) {
                ps.setLong(1, courseId);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        conversations.add(new Conversation(rs.getString("first_message"),
                                LocalDateTime.parse(rs.getString("started_at"))));
                    }
                }
            }
            return conversations;
        });
    }
}
