package studybuddy.domain;

import java.util.Objects;

/**
 * A course (F01). Its materials, concepts, quizzes and flashcards refer to it by courseId
 * and are loaded through their repositories ({@code findByCourse}).
 */
public class Course {

    private long id;
    private String code;
    private String name;

    public Course(long id, String code, String name) {
        this.id = id;
        setCode(code);
        setName(name);
    }

    /** Creates a course that has not been saved yet (id 0). */
    public static Course create(String code, String name) {
        return new Course(0, code, name);
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
        this.name = name == null ? "" : name.trim();
    }

    public String getCode() {
        return code;
    }

    public void setCode(String code) {
        if (code == null || code.isBlank()) {
            throw new IllegalArgumentException("Course code is required");
        }
        this.code = code.trim();
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
        return code + (name.isEmpty() ? "" : " - " + name);
    }
}
