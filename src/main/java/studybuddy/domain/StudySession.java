package studybuddy.domain;

import java.time.LocalDate;
import java.util.List;

/** One dated block of a study plan (F11). */
public class StudySession {

    private long id;
    private LocalDate date;
    private int minutes;
    private final List<String> concepts;
    private String activities;
    private boolean done;

    public StudySession(long id, LocalDate date, int minutes, List<String> concepts, String activities, boolean done) {
        if (minutes <= 0) {
            throw new IllegalArgumentException("A session must last at least one minute");
        }
        this.id = id;
        this.date = date;
        this.minutes = minutes;
        this.concepts = List.copyOf(concepts);
        this.activities = activities == null ? "" : activities;
        this.done = done;
    }

    public long getId() {
        return id;
    }

    public void setId(long id) {
        this.id = id;
    }

    public LocalDate getDate() {
        return date;
    }

    public int getMinutes() {
        return minutes;
    }

    /** Names of the concepts covered in this session. */
    public List<String> getConcepts() {
        return concepts;
    }

    public String getActivities() {
        return activities;
    }

    public void setActivities(String activities) {
        this.activities = activities == null ? "" : activities;
    }

    public boolean isDone() {
        return done;
    }

    public void markDone() {
        done = true;
    }
}
