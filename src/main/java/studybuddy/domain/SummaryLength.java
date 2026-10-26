package studybuddy.domain;

import java.util.Locale;

/** Summary length option (F02). maxKeyPoints is passed to the prompt and enforced on the response. */
public enum SummaryLength {
    BRIEF(4),
    STANDARD(8),
    DETAILED(15);

    private final int maxKeyPoints;

    SummaryLength(int maxKeyPoints) {
        this.maxKeyPoints = maxKeyPoints;
    }

    public int maxKeyPoints() {
        return maxKeyPoints;
    }

    public static SummaryLength fromString(String value) {
        try {
            return valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException | NullPointerException e) {
            throw new IllegalArgumentException("Unknown length '" + value + "' (expected brief, standard or detailed)");
        }
    }
}
