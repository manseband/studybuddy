package studybuddy.domain;

/**
 * Something the Study Coach created (F12): remembered for later messages and listed by the CLI.
 *
 * @param id    the quiz id or flashcard deck id
 * @param label what to show, e.g. "Quiz #7 (5 questions)"
 */
public record CreatedItem(Type type, long id, String label) {

    public enum Type {
        QUIZ,
        DECK
    }
}
