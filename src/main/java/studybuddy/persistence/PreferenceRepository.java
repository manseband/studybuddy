package studybuddy.persistence;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/** SQLite storage for the Study Coach's profile of the student's stated preferences (F09), kept across sessions. */
public class PreferenceRepository {

    private final Database db;

    public PreferenceRepository(Database db) {
        this.db = db;
    }

    /** Stores the preference; storing the same text again changes nothing. */
    public void add(String preference) {
        db.withConnection(c -> {
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT OR IGNORE INTO agent_preferences(preference, created_at) VALUES (?, ?)")) {
                ps.setString(1, preference);
                ps.setString(2, LocalDateTime.now().toString());
                return ps.executeUpdate();
            }
        });
    }

    /** The most recent preferences, oldest of them first. */
    public List<String> findRecent(int limit) {
        return db.withConnection(c -> {
            List<String> preferences = new ArrayList<>();
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT preference FROM agent_preferences ORDER BY id DESC LIMIT ?")) {
                ps.setInt(1, limit);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        preferences.add(rs.getString(1));
                    }
                }
            }
            return preferences.reversed();
        });
    }
}
