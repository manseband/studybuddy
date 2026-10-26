package studybuddy.domain;

/** Direction of a concept's recent mastery evidence (F09). */
public enum Trend {
    UP,
    DOWN,
    FLAT;

    public String arrow() {
        return switch (this) {
            case UP -> "↑";
            case DOWN -> "↓";
            case FLAT -> "→";
        };
    }
}
