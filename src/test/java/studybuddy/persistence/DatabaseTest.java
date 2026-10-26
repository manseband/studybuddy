package studybuddy.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DatabaseTest {

    @TempDir
    Path dir;

    private static int count(Database db, String table) {
        return db.withConnection(c -> {
            try (Statement st = c.createStatement(); ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM " + table)) {
                rs.next();
                return rs.getInt(1);
            }
        });
    }

    private static long insertCourse(Connection c, String name) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("INSERT INTO courses(name) VALUES (?)",
                Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, name);
            ps.executeUpdate();
            try (ResultSet keys = ps.getGeneratedKeys()) {
                keys.next();
                return keys.getLong(1);
            }
        }
    }

    @Test
    void createsFileAndSchemaAndCanReopen() {
        Path file = dir.resolve("nested/folder/test.db");
        try (Database db = Database.open(file)) {
            assertEquals(Database.SCHEMA_VERSION, db.schemaVersion());
            db.withConnection(c -> insertCourse(c, "EECS3311"));
        }
        assertTrue(Files.exists(file));
        try (Database db = Database.open(file)) {
            assertEquals(1, count(db, "courses"));
        }
    }

    @Test
    void failedTransactionIsRolledBack() {
        try (Database db = Database.inMemory()) {
            assertThrows(IllegalStateException.class, () -> db.inTransaction(c -> {
                insertCourse(c, "EECS3311");
                throw new IllegalStateException("simulated failure halfway through");
            }));
            assertEquals(0, count(db, "courses"));
        }
    }

    @Test
    void foreignKeysCascadeFromCourseToChildren() {
        try (Database db = Database.inMemory()) {
            db.inTransaction(c -> {
                long courseId = insertCourse(c, "EECS3311");
                try (Statement st = c.createStatement()) {
                    st.executeUpdate("INSERT INTO materials(course_id, title, file_type, status, content_hash, added_at)"
                            + " VALUES (" + courseId + ", 'notes', 'txt', 'INDEXED', 'h1', '2026-10-26T10:00')");
                    st.executeUpdate("DELETE FROM courses WHERE id = " + courseId);
                }
                return null;
            });
            assertEquals(0, count(db, "materials"));
        }
    }

    @Test
    void courseNamesAreUniqueIgnoringCase() {
        try (Database db = Database.inMemory()) {
            db.withConnection(c -> insertCourse(c, "EECS3311"));
            assertThrows(PersistenceException.class, () -> db.withConnection(c -> insertCourse(c, "eecs3311")));
        }
    }

    @Test
    void schemaSplitterSkipsComments() {
        List<String> statements = Database.splitStatements("-- comment; ignored\nCREATE TABLE a (x INT);\n\nCREATE TABLE b (y INT);");
        assertEquals(List.of("CREATE TABLE a (x INT)", "CREATE TABLE b (y INT)"), statements);
    }
}
