package studybuddy.domain;

/**
 * A quiz question (F06). Subclasses add the answer key for their type and decide whether
 * a generated question is well formed ({@link #isValid()}); invalid questions are regenerated.
 * conceptId is null for an untagged question (excluded from mastery).
 * sourceChunkId points at the excerpt the question was written from (used by F08).
 */
public abstract class Question {

    private long id;
    private final String stem;
    private final Difficulty difficulty;
    private final int sourcePage;
    private Long conceptId;
    private final Long sourceChunkId;

    protected Question(long id, String stem, Difficulty difficulty, int sourcePage, Long conceptId,
                       Long sourceChunkId) {
        this.id = id;
        this.stem = stem == null ? "" : stem.trim();
        this.difficulty = difficulty;
        this.sourcePage = sourcePage;
        this.conceptId = conceptId;
        this.sourceChunkId = sourceChunkId;
    }

    /** True if the question is well formed for its type. */
    public abstract boolean isValid();

    public abstract QuestionType getType();

    /** The correct answer as shown to the student in results. */
    public abstract String correctAnswerText();

    /** Common checks shared by all types. */
    protected boolean hasValidBase() {
        return !stem.isEmpty() && difficulty != null && sourcePage >= 1;
    }

    public long getId() {
        return id;
    }

    public void setId(long id) {
        this.id = id;
    }

    public String getStem() {
        return stem;
    }

    public Difficulty getDifficulty() {
        return difficulty;
    }

    public int getSourcePage() {
        return sourcePage;
    }

    public Long getConceptId() {
        return conceptId;
    }

    /** Set to null when the concept is deleted, which makes the question untagged. */
    public void setConceptId(Long conceptId) {
        this.conceptId = conceptId;
    }

    public Long getSourceChunkId() {
        return sourceChunkId;
    }

    @Override
    public String toString() {
        return stem;
    }
}
