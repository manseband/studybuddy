package studybuddy.persistence;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import studybuddy.domain.Course;

/** SQLite storage for courses. Deleting a course also removes its materials, quizzes, cards and plans. */
public class CourseRepository implements Repository<Course> {

    private static final String COLUMNS = "id, name, code, exam_date";

    private final Database db;

    public CourseRepository(Database db) {
        this.db = db;
    }

    @Override
    public Course save(Course course) {
        return db.withConnection(c -> {
            if (course.getId() == 0) {
                try (PreparedStatement ps = c.prepareStatement(
                        "INSERT INTO courses(name, code, exam_date) VALUES (?, ?, ?)",
                        Statement.RETURN_GENERATED_KEYS)) {
                    bind(ps, course);
                    ps.executeUpdate();
                    course.setId(SqlUtil.generatedId(ps));
                }
            } else {
                try (PreparedStatement ps = c.prepareStatement(
                        "UPDATE courses SET name = ?, code = ?, exam_date = ? WHERE id = ?")) {
                    bind(ps, course);
                    ps.setLong(4, course.getId());
                    ps.executeUpdate();
                }
            }
            return course;
        });
    }

    @Override
    public Optional<Course> findById(long id) {
        return db.withConnection(c -> queryOne(c, "SELECT " + COLUMNS + " FROM courses WHERE id = ?", id));
    }

    /** A course is its own course: returns it as a single-element list, or an empty list. */
    @Override
    public List<Course> findByCourse(long courseId) {
        return findById(courseId).map(List::of).orElse(List.of());
    }

    public List<Course> findAll() {
        return db.withConnection(c -> {
            List<Course> courses = new ArrayList<>();
            try (Statement st = c.createStatement();
                 ResultSet rs = st.executeQuery("SELECT " + COLUMNS + " FROM courses ORDER BY name COLLATE NOCASE")) {
                while (rs.next()) {
                    courses.add(map(rs));
                }
            }
            return courses;
        });
    }

    /** Case-insensitive lookup by name, matching the UNIQUE NOCASE constraint. */
    public Optional<Course> findByName(String name) {
        return db.withConnection(c -> queryOne(c,
                "SELECT " + COLUMNS + " FROM courses WHERE name = ? COLLATE NOCASE", name.trim()));
    }

    @Override
    public void delete(long id) {
        db.withConnection(c -> {
            try (PreparedStatement ps = c.prepareStatement("DELETE FROM courses WHERE id = ?")) {
                ps.setLong(1, id);
                return ps.executeUpdate();
            }
        });
    }

    private static Optional<Course> queryOne(Connection c, String sql, Object param) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setObject(1, param);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(map(rs)) : Optional.empty();
            }
        }
    }

    private static void bind(PreparedStatement ps, Course course) throws SQLException {
        ps.setString(1, course.getName());
        ps.setString(2, course.getCode());
        ps.setString(3, course.getExamDate() == null ? null : course.getExamDate().toString());
    }

    private static Course map(ResultSet rs) throws SQLException {
        String exam = rs.getString("exam_date");
        return new Course(rs.getLong("id"), rs.getString("name"), rs.getString("code"),
                exam == null ? null : LocalDate.parse(exam));
    }
}
