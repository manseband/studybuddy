package studybuddy.persistence;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import studybuddy.domain.Difficulty;
import studybuddy.domain.MultipleChoiceQuestion;
import studybuddy.domain.Question;
import studybuddy.domain.QuestionType;
import studybuddy.domain.Quiz;
import studybuddy.domain.ShortAnswerQuestion;
import studybuddy.domain.TrueFalseQuestion;

/** SQLite storage for quizzes and their questions (F05). The type-specific answer key is stored as JSON. */
public class QuizRepository implements Repository<Quiz> {

    private final Database db;
    private final ObjectMapper mapper = new ObjectMapper();

    public QuizRepository(Database db) {
        this.db = db;
    }

    /** A quiz is never edited once generated, so save always inserts it with all its questions. */
    @Override
    public Quiz save(Quiz quiz) {
        if (quiz.getId() != 0) {
            throw new IllegalArgumentException("A generated quiz cannot be changed");
        }
        return db.inTransaction(c -> {
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO quizzes(course_id, created_at, difficulty) VALUES (?, ?, ?)",
                    Statement.RETURN_GENERATED_KEYS)) {
                ps.setLong(1, quiz.getCourseId());
                ps.setString(2, quiz.getCreatedAt().toString());
                ps.setString(3, quiz.getDifficulty().name());
                ps.executeUpdate();
                quiz.setId(SqlUtil.generatedId(ps));
            }
            int position = 0;
            for (Question question : quiz.getQuestions()) {
                insertQuestion(c, quiz.getId(), position++, question);
            }
            return quiz;
        });
    }

    @Override
    public Optional<Quiz> findById(long id) {
        return query("SELECT * FROM quizzes WHERE id = ?", id).stream().findFirst();
    }

    /** Most recent first. */
    @Override
    public List<Quiz> findByCourse(long courseId) {
        return query("SELECT * FROM quizzes WHERE course_id = ? ORDER BY id DESC", courseId);
    }

    @Override
    public void delete(long id) {
        db.withConnection(c -> {
            try (PreparedStatement ps = c.prepareStatement("DELETE FROM quizzes WHERE id = ?")) {
                ps.setLong(1, id);
                return ps.executeUpdate();
            }
        });
    }

    /** Question stems from the course's most recent quizzes, newest first (F05: avoid repeats). */
    public List<String> findRecentStems(long courseId, int quizCount) {
        return db.withConnection(c -> {
            List<String> stems = new ArrayList<>();
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT stem FROM questions WHERE quiz_id IN "
                            + "(SELECT id FROM quizzes WHERE course_id = ? ORDER BY id DESC LIMIT ?) "
                            + "ORDER BY quiz_id DESC, position")) {
                ps.setLong(1, courseId);
                ps.setInt(2, quizCount);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        stems.add(rs.getString(1));
                    }
                }
            }
            return stems;
        });
    }

    private void insertQuestion(Connection c, long quizId, int position, Question question) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "INSERT INTO questions(quiz_id, position, type, stem, difficulty, source_page, concept_id, "
                        + "source_chunk_id, answer_key) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                Statement.RETURN_GENERATED_KEYS)) {
            ps.setLong(1, quizId);
            ps.setInt(2, position);
            ps.setString(3, question.getType().code());
            ps.setString(4, question.getStem());
            ps.setString(5, question.getDifficulty().name());
            ps.setInt(6, question.getSourcePage());
            ps.setObject(7, question.getConceptId());
            ps.setObject(8, question.getSourceChunkId());
            ps.setString(9, answerKey(question));
            ps.executeUpdate();
            question.setId(SqlUtil.generatedId(ps));
        }
    }

    private String answerKey(Question question) throws SQLException {
        Map<String, Object> key = switch (question) {
            case MultipleChoiceQuestion q -> Map.of("options", q.getOptions(), "correctIndex", q.getCorrectIndex());
            case TrueFalseQuestion q -> Map.of("answer", q.getCorrectAnswer());
            case ShortAnswerQuestion q -> Map.of("referenceAnswer", q.getReferenceAnswer(), "rubric", q.getRubric());
            default -> throw new SQLException("Unknown question class " + question.getClass());
        };
        try {
            return mapper.writeValueAsString(key);
        } catch (JsonProcessingException e) {
            throw new SQLException("Could not store the answer key", e);
        }
    }

    private List<Quiz> query(String sql, long param) {
        return db.withConnection(c -> {
            List<Quiz> quizzes = new ArrayList<>();
            try (PreparedStatement ps = c.prepareStatement(sql)) {
                ps.setLong(1, param);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        quizzes.add(new Quiz(rs.getLong("id"), rs.getLong("course_id"),
                                LocalDateTime.parse(rs.getString("created_at")),
                                Difficulty.valueOf(rs.getString("difficulty"))));
                    }
                }
            }
            for (Quiz quiz : quizzes) {
                loadQuestions(c, quiz);
            }
            return quizzes;
        });
    }

    private void loadQuestions(Connection c, Quiz quiz) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT * FROM questions WHERE quiz_id = ? ORDER BY position")) {
            ps.setLong(1, quiz.getId());
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    quiz.addQuestion(toQuestion(rs));
                }
            }
        }
    }

    private Question toQuestion(ResultSet rs) throws SQLException {
        long id = rs.getLong("id");
        String stem = rs.getString("stem");
        Difficulty difficulty = Difficulty.valueOf(rs.getString("difficulty"));
        int page = rs.getInt("source_page");
        Long conceptId = SqlUtil.nullableLong(rs, "concept_id");
        Long chunkId = SqlUtil.nullableLong(rs, "source_chunk_id");
        JsonNode key;
        try {
            key = mapper.readTree(rs.getString("answer_key"));
        } catch (JsonProcessingException e) {
            throw new SQLException("Stored answer key is not valid JSON", e);
        }
        return switch (QuestionType.fromCode(rs.getString("type"))) {
            case MULTIPLE_CHOICE -> {
                List<String> options = new ArrayList<>();
                key.get("options").forEach(option -> options.add(option.asText()));
                yield new MultipleChoiceQuestion(id, stem, difficulty, page, conceptId, chunkId, options,
                        key.get("correctIndex").asInt());
            }
            case TRUE_FALSE -> new TrueFalseQuestion(id, stem, difficulty, page, conceptId, chunkId,
                    key.get("answer").asBoolean());
            case SHORT_ANSWER -> new ShortAnswerQuestion(id, stem, difficulty, page, conceptId, chunkId,
                    key.get("referenceAnswer").asText(), key.get("rubric").asText());
        };
    }
}
