package studybuddy.domain;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * A day-by-day study plan up to an exam (F11).
 * omittedConcepts lists concepts that did not fit into the available time.
 */
public class StudyPlan {

    private long id;
    private long courseId;
    private LocalDate examDate;
    private LocalDateTime createdAt;
    private final List<StudySession> sessions = new ArrayList<>();
    private final List<String> omittedConcepts = new ArrayList<>();

    public StudyPlan(long id, long courseId, LocalDate examDate, LocalDateTime createdAt) {
        this.id = id;
        this.courseId = courseId;
        this.examDate = examDate;
        this.createdAt = createdAt;
    }

    /** Share of planned minutes already done, from 0.0 to 1.0. An empty plan counts as 0. */
    public double getCompletion() {
        int total = sessions.stream().mapToInt(StudySession::getMinutes).sum();
        if (total == 0) {
            return 0.0;
        }
        int done = sessions.stream().filter(StudySession::isDone).mapToInt(StudySession::getMinutes).sum();
        return (double) done / total;
    }

    public void addSession(StudySession session) {
        sessions.add(session);
        sessions.sort(Comparator.comparing(StudySession::getDate));
    }

    public Optional<StudySession> findSession(long sessionId) {
        return sessions.stream().filter(s -> s.getId() == sessionId).findFirst();
    }

    /** Sessions on or after the given date that are not done yet (used by Replan). */
    public List<StudySession> remainingFrom(LocalDate date) {
        return sessions.stream().filter(s -> !s.isDone() && !s.getDate().isBefore(date)).toList();
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

    public LocalDate getExamDate() {
        return examDate;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public List<StudySession> getSessions() {
        return List.copyOf(sessions);
    }

    public List<String> getOmittedConcepts() {
        return List.copyOf(omittedConcepts);
    }

    public void setOmittedConcepts(List<String> concepts) {
        omittedConcepts.clear();
        omittedConcepts.addAll(concepts);
    }
}
