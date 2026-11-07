package studybuddy.domain;

import java.util.Locale;

/** Kinds of entries in the study history (F13), used by the history filter. */
public enum ActivityType {
    MATERIAL("Materials"),
    QUESTION("Questions"),
    QUIZ("Quizzes"),
    FLASHCARDS("Flashcards"),
    COACH("Coach");

    private final String label;

    ActivityType(String label) {
        this.label = label;
    }

    /** The name shown in the history filter. */
    public String label() {
        return label;
    }

    public static ActivityType fromString(String value) {
        return switch (value == null ? "" : value.trim().toLowerCase(Locale.ROOT)) {
            case "material", "materials" -> MATERIAL;
            case "question", "questions" -> QUESTION;
            case "quiz", "quizzes" -> QUIZ;
            case "flashcard", "flashcards" -> FLASHCARDS;
            case "coach" -> COACH;
            default -> throw new IllegalArgumentException("Unknown activity type '" + value
                    + "' (expected material, question, quiz, flashcards or coach)");
        };
    }
}
