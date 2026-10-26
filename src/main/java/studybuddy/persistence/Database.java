package studybuddy.persistence;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Arrays;
import java.util.List;

/**
 * Owns the single SQLite connection used by all repositories and creates the schema on first use.
 *
 * StudyBuddy is a single-user desktop app, so one connection is enough. Access is synchronized on this
 * object so that GUI background tasks cannot interleave statements; {@link #inTransaction} runs several
 * statements as one unit and rolls back if any of them fails (F01: no half-ingested material).
 */
public class Database implements AutoCloseable {

    public static final int SCHEMA_VERSION = 1;
    private static final String SCHEMA_RESOURCE = "/schema.sql";

    /** Work done with the connection that may throw SQLException. */
    @FunctionalInterface
    public interface SqlWork<T> {
        T run(Connection connection) throws SQLException;
    }

    private final Connection connection;
    private int transactionDepth;

    private Database(String jdbcUrl) {
        try {
            connection = DriverManager.getConnection(jdbcUrl);
            try (Statement st = connection.createStatement()) {
                st.execute("PRAGMA foreign_keys = ON");
            }
            createSchema();
        } catch (SQLException e) {
            throw new PersistenceException("Could not open the database (" + jdbcUrl + ")", e);
        }
    }

    /** Opens (and if needed creates) the database file, including missing parent folders. */
    public static Database open(Path file) {
        try {
            Path parent = file.toAbsolutePath().getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
        } catch (IOException e) {
            throw new PersistenceException("Could not create folder for database " + file, e);
        }
        return new Database("jdbc:sqlite:" + file.toAbsolutePath());
    }

    /** A private in-memory database, used by tests. */
    public static Database inMemory() {
        return new Database("jdbc:sqlite::memory:");
    }

    /** Runs work with the connection; errors are wrapped in PersistenceException. */
    public synchronized <T> T withConnection(SqlWork<T> work) {
        try {
            return work.run(connection);
        } catch (SQLException e) {
            throw new PersistenceException("Database error: " + e.getMessage(), e);
        }
    }

    /**
     * Runs work in a transaction: commits if it returns normally, rolls back if it throws.
     * Nested calls join the outer transaction.
     */
    public synchronized <T> T inTransaction(SqlWork<T> work) {
        if (transactionDepth > 0) {
            transactionDepth++;
            try {
                return withConnection(work);
            } finally {
                transactionDepth--;
            }
        }
        try {
            connection.setAutoCommit(false);
            transactionDepth = 1;
            T result = work.run(connection);
            connection.commit();
            return result;
        } catch (SQLException | RuntimeException e) {
            rollbackQuietly();
            if (e instanceof RuntimeException re) {
                throw re;
            }
            throw new PersistenceException("Database error, changes rolled back: " + e.getMessage(), e);
        } finally {
            transactionDepth = 0;
            try {
                connection.setAutoCommit(true);
            } catch (SQLException ignored) {
                // connection is unusable; the next call will report the error
            }
        }
    }

    public int schemaVersion() {
        return withConnection(c -> {
            try (Statement st = c.createStatement(); var rs = st.executeQuery("PRAGMA user_version")) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        });
    }

    private void createSchema() throws SQLException {
        String script;
        try (InputStream in = Database.class.getResourceAsStream(SCHEMA_RESOURCE)) {
            if (in == null) {
                throw new PersistenceException(SCHEMA_RESOURCE + " not found on the classpath");
            }
            script = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new PersistenceException("Could not read " + SCHEMA_RESOURCE, e);
        }
        try (Statement st = connection.createStatement()) {
            for (String sql : splitStatements(script)) {
                st.execute(sql);
            }
            st.execute("PRAGMA user_version = " + SCHEMA_VERSION);
        }
    }

    /** Splits the schema script on semicolons after removing "--" comment lines. */
    static List<String> splitStatements(String script) {
        StringBuilder withoutComments = new StringBuilder();
        for (String line : script.split("\\R")) {
            if (!line.strip().startsWith("--")) {
                withoutComments.append(line).append('\n');
            }
        }
        return Arrays.stream(withoutComments.toString().split(";"))
                .map(String::strip)
                .filter(s -> !s.isEmpty())
                .toList();
    }

    private void rollbackQuietly() {
        try {
            connection.rollback();
        } catch (SQLException ignored) {
            // the original error is more useful to the caller
        }
    }

    @Override
    public synchronized void close() {
        try {
            connection.close();
        } catch (SQLException e) {
            throw new PersistenceException("Could not close the database", e);
        }
    }
}
