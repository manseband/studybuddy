package studybuddy.domain;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * A generated quiz (F06). difficulty is the fixed level chosen by the student, or null when the
 * quiz is adaptive (each question then carries its own level from the adaptive policy, F10).
 */
public class Quiz {

    private long id;
    private long courseId;
    private LocalDateTime createdAt;
    private Difficulty difficulty;
    private final List<Question> questions = new ArrayList<>();

    public Quiz(long id, long courseId, LocalDateTime createdAt, Difficulty difficulty) {
        this.id = id;
        this.courseId = courseId;
        this.createdAt = createdAt;
        this.difficulty = difficulty;
    }

    public List<Question> getQuestions() {
        return List.copyOf(questions);
    }

    public void addQuestion(Question question) {
        questions.add(question);
    }

    public Optional<Question> findQuestion(long questionId) {
        return questions.stream().filter(q -> q.getId() == questionId).findFirst();
    }

    public boolean isAdaptive() {
        return difficulty == null;
    }

    public long getId() {
        return id;
    }

    public void setId(long id) {
        this.id = id;
    }

    public long getCourseId() {
        return courseId;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public Difficulty getDifficulty() {
        return difficulty;
    }

    public int size() {
        return questions.size();
    }
}
