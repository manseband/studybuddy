package studybuddy.domain;

import java.util.Locale;

/** Question difficulty. The weight makes harder questions count more in mastery (F09). */
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

    /** One level up, staying at HARD. Used by adaptive practice (F10). */
    public Difficulty harder() {
        return this == EASY ? MEDIUM : HARD;
    }

    /** One level down, staying at EASY. */
    public Difficulty easier() {
        return this == HARD ? MEDIUM : EASY;
    }

    public static Difficulty fromString(String value) {
        try {
            return valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException | NullPointerException e) {
            throw new IllegalArgumentException("Unknown difficulty '" + value + "' (expected easy, medium or hard)");
        }
    }
}
