package studybuddy.domain;

import java.util.Locale;

/** Question difficulty. The weight makes harder questions count more in mastery (F08). */
public enum Difficulty {
    EASY(1.0),
    MEDIUM(1.5),
    HARD(2.0);

    private final double weight;

    Difficulty(double weight) {
        this.weight = weight;
    }

    public double weight() {
        return weight;
    }

    public static Difficulty fromString(String value) {
        try {
            return valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException | NullPointerException e) {
            throw new IllegalArgumentException("Unknown difficulty '" + value + "' (expected easy, medium or hard)");
        }
    }
}
