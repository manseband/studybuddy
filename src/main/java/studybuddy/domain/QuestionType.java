package studybuddy.domain;

import java.util.Locale;

/** Quiz question types (F06). The code is the short form used on the CLI (--types mcq,tf,short). */
public enum QuestionType {
    MULTIPLE_CHOICE("mcq"),
    TRUE_FALSE("tf"),
    SHORT_ANSWER("short");

    private final String code;

    QuestionType(String code) {
        this.code = code;
    }

    public String code() {
        return code;
    }

    /** True for types graded by direct comparison with the answer key (F07). */
    public boolean isObjective() {
        return this != SHORT_ANSWER;
    }

    /** Accepts the CLI code ("mcq") or the enum name ("MULTIPLE_CHOICE"). */
    public static QuestionType fromCode(String value) {
        String v = value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
        for (QuestionType type : values()) {
            if (type.code.equals(v) || type.name().toLowerCase(Locale.ROOT).equals(v)) {
                return type;
            }
        }
        throw new IllegalArgumentException("Unknown question type '" + value + "' (expected mcq, tf or short)");
    }
}
