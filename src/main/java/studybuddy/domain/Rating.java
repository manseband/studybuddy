package studybuddy.domain;

import java.util.Locale;

/** Flashcard recall rating (F05). */
public enum Rating {
    AGAIN(0.0),
    HARD(0.5),
    GOOD(0.8),
    EASY(1.0);

    private final double evidenceScore;

    Rating(double evidenceScore) {
        this.evidenceScore = evidenceScore;
    }

    /** Score in [0, 1] recorded as mastery evidence for the card's concept (F09). */
    public double evidenceScore() {
        return evidenceScore;
    }

    public static Rating fromString(String value) {
        try {
            return valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException | NullPointerException e) {
            throw new IllegalArgumentException("Unknown rating '" + value + "' (expected again, hard, good or easy)");
        }
    }
}
