package studybuddy.persistence;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import studybuddy.domain.AttemptStatus;
import studybuddy.domain.GradeResult;
import studybuddy.domain.QuizAttempt;

/** SQLite storage for quiz attempts (F07): one row per attempt and one per question of the attempt. */
public class AttemptRepository implements Repository<QuizAttempt> {

    private final Database db;

    public AttemptRepository(Database db) {
        this.db = db;
    }

    @Override
    public QuizAttempt save(QuizAttempt attempt) {
        return db.inTransaction(c -> {
            if (attempt.getId() == 0) {
                try (PreparedStatement ps = c.prepareStatement(
                        "INSERT INTO quiz_attempts(quiz_id, course_id, status, submitted_at) VALUES (?, ?, ?, ?)",
                        Statement.RETURN_GENERATED_KEYS)) {
                    ps.setLong(1, attempt.getQuizId());
                    ps.setLong(2, attempt.getCourseId());
                    ps.setString(3, attempt.getStatus().name());
                    ps.setString(4, timestamp(attempt));
                    ps.executeUpdate();
                    attempt.setId(SqlUtil.generatedId(ps));
                }
            } else {
                try (PreparedStatement ps = c.prepareStatement(
                        "UPDATE quiz_attempts SET status = ?, submitted_at = ? WHERE id = ?")) {
                    ps.setString(1, attempt.getStatus().name());
                    ps.setString(2, timestamp(attempt));
                    ps.setLong(3, attempt.getId());
                    ps.executeUpdate();
                }
            }
            for (long questionId : attempt.getQuestionIds()) {
                saveAnswer(c, attempt, questionId);
            }
            return attempt;
        });
    }

    @Override
    public Optional<QuizAttempt> findById(long id) {
        return query("SELECT * FROM quiz_attempts WHERE id = ?", id).stream().findFirst();
    }

    /** Most recent first. */
    @Override
    public List<QuizAttempt> findByCourse(long courseId) {
        return query("SELECT * FROM quiz_attempts WHERE course_id = ? ORDER BY id DESC", courseId);
    }

    @Override
    public void delete(long id) {
        db.withConnection(c -> {
            try (PreparedStatement ps = c.prepareStatement("DELETE FROM quiz_attempts WHERE id = ?")) {
                ps.setLong(1, id);
                return ps.executeUpdate();
            }
        });
    }

    private void saveAnswer(Connection c, QuizAttempt attempt, long questionId) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "INSERT OR REPLACE INTO attempt_answers(attempt_id, question_id, answer, score, correct, feedback, "
                        + "graded_by_ai, pending, overridden) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)")) {
            ps.setLong(1, attempt.getId());
            ps.setLong(2, questionId);
            ps.setString(3, attempt.answerFor(questionId));
            GradeResult result = attempt.resultFor(questionId).orElse(null);
            ps.setObject(4, result == null ? null : result.score());
            ps.setObject(5, result == null ? null : toInt(result.correct()));
            ps.setString(6, result == null ? null : result.feedback());
            ps.setObject(7, result == null ? null : toInt(result.gradedByAI()));
            ps.setObject(8, result == null ? null : toInt(result.pending()));
            ps.setObject(9, result == null ? null : toInt(result.overridden()));
            ps.executeUpdate();
        }
    }

    private List<QuizAttempt> query(String sql, long param) {
        return db.withConnection(c -> {
            List<QuizAttempt> attempts = new ArrayList<>();
            try (PreparedStatement ps = c.prepareStatement(sql)) {
                ps.setLong(1, param);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        attempts.add(new QuizAttempt(rs.getLong("id"), rs.getLong("quiz_id"), rs.getLong("course_id"),
                                List.of(), AttemptStatus.valueOf(rs.getString("status")),
                                parseTime(rs.getString("submitted_at"))));
                    }
                }
            }
            List<QuizAttempt> loaded = new ArrayList<>();
            for (QuizAttempt attempt : attempts) {
                loaded.add(withAnswers(c, attempt));
            }
            return loaded;
        });
    }

    /** Rebuilds the attempt with its questions in quiz order, its answers and its grades. */
    private QuizAttempt withAnswers(Connection c, QuizAttempt attempt) throws SQLException {
        List<Long> questionIds = new ArrayList<>();
        Map<Long, String> answers = new LinkedHashMap<>();
        Map<Long, GradeResult> results = new LinkedHashMap<>();
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT a.* FROM attempt_answers a JOIN questions q ON q.id = a.question_id "
                        + "WHERE a.attempt_id = ? ORDER BY q.position")) {
            ps.setLong(1, attempt.getId());
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    long questionId = rs.getLong("question_id");
                    questionIds.add(questionId);
                    answers.put(questionId, rs.getString("answer"));
                    if (rs.getObject("score") != null) {
                        results.put(questionId, new GradeResult(questionId, rs.getDouble("score"),
                                rs.getInt("correct") == 1, rs.getString("feedback"), rs.getInt("graded_by_ai") == 1,
                                rs.getInt("pending") == 1, rs.getInt("overridden") == 1));
                    }
                }
            }
        }
        QuizAttempt full = new QuizAttempt(attempt.getId(), attempt.getQuizId(), attempt.getCourseId(), questionIds,
                attempt.getStatus(), attempt.getSubmittedAt());
        full.restore(answers, results);
        return full;
    }

    private static String timestamp(QuizAttempt attempt) {
        return attempt.getSubmittedAt() == null ? null : attempt.getSubmittedAt().toString();
    }

    private static LocalDateTime parseTime(String value) {
        return value == null ? null : LocalDateTime.parse(value);
    }

    private static int toInt(boolean value) {
        return value ? 1 : 0;
    }
}
