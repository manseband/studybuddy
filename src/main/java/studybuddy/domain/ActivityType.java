package studybuddy.domain;

import java.util.Locale;

/** Kinds of entries in the study history (F13), used by the --type filter. */
public enum ActivityType {
    MATERIAL,
    CONCEPTS,
    QUESTION,
    QUIZ,
    FLASHCARD_REVIEW,
    PLAN,
    COACH;

    public static ActivityType fromString(String value) {
        String v = value == null ? "" : value.trim().toUpperCase(Locale.ROOT).replace('-', '_');
        if (v.equals("FLASHCARDS") || v.equals("REVIEW")) {
            return FLASHCARD_REVIEW;
        }
        try {
            return valueOf(v);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Unknown activity type '" + value + "'");
        }
    }
}
