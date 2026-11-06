package studybuddy.domain;

import java.util.Objects;

/**
 * A course (F01). Its materials, concepts, quizzes and flashcards refer to it by courseId
 * and are loaded through their repositories ({@code findByCourse}).
 */
public class Course {

    private long id;
    private String name;
    private String code;

    public Course(long id, String name, String code) {
        this.id = id;
        setName(name);
        this.code = code == null ? "" : code.trim();
    }

    /** Creates a course that has not been saved yet (id 0). */
    public static Course create(String name, String code) {
        return new Course(0, name, code);
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
