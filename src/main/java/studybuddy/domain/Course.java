package studybuddy.domain;

import java.time.LocalDate;
import java.util.Objects;

/**
 * A course (F01). Its materials, concepts, quizzes, flashcards and plans refer to it by courseId
 * and are loaded through their repositories ({@code findByCourse}).
 */
public class Course {

    private long id;
    private String name;
    private String code;
    private LocalDate examDate;

    public Course(long id, String name, String code, LocalDate examDate) {
        this.id = id;
        setName(name);
        this.code = code == null ? "" : code.trim();
        this.examDate = examDate;
    }

    /** Creates a course that has not been saved yet (id 0). */
    public static Course create(String name, String code, LocalDate examDate) {
        return new Course(0, name, code, examDate);
    }

    public long getId() {
        return id;
    }

    public void setId(long id) {
        this.id = id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("Course name is required");
        }
        this.name = name.trim();
    }

    public String getCode() {
        return code;
    }

    public void setCode(String code) {
        this.code = code == null ? "" : code.trim();
    }

    public LocalDate getExamDate() {
        return examDate;
    }

    public void setExamDate(LocalDate examDate) {
        this.examDate = examDate;
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof Course other && id != 0 && id == other.id;
    }

    @Override
    public int hashCode() {
        return Objects.hash(id);
    }

    @Override
    public String toString() {
        return name + (code.isEmpty() ? "" : " (" + code + ")");
    }
}
