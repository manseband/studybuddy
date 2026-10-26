package studybuddy.domain;

/** Ingestion status of a material (F01). */
public enum MaterialStatus {
    PROCESSING("Processing"),
    INDEXED("Indexed"),
    NO_TEXT("No text found");

    private final String label;

    MaterialStatus(String label) {
        this.label = label;
    }

    /** Text shown in the materials table and CLI. */
    public String label() {
        return label;
    }
}
