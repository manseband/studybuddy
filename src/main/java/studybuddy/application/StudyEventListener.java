package studybuddy.application;

/** Observer of study events (e.g. MainWindow, which refreshes the affected panels). */
public interface StudyEventListener {

    void onStudyEvent(StudyEvent event);
}
