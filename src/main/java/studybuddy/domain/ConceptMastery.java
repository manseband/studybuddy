package studybuddy.domain;

/**
 * Mastery of one concept (F08): score 0-100 from quiz and flashcard evidence.
 * A concept with fewer than {@link #MIN_EVIDENCE} evidence events is "insufficient data", never weak.
 */
public record ConceptMastery(long conceptId, String conceptName, double score, int evidenceCount, Trend trend,
                             boolean weak) {

    public static final int MIN_EVIDENCE = 3;

    public ConceptMastery {
        if (score < 0 || score > 100) {
            throw new IllegalArgumentException("Mastery score must be between 0 and 100");
        }
        if (evidenceCount < MIN_EVIDENCE && weak) {
            throw new IllegalArgumentException("A concept with insufficient data cannot be flagged weak");
        }
    }

    public boolean insufficientData() {
        return evidenceCount < MIN_EVIDENCE;
    }

    /** "Weak", "Strong" or "Not enough data", as shown to the student. */
    public String statusLabel() {
        if (insufficientData()) {
            return "Not enough data";
        }
        return weak ? "Weak" : "Strong";
    }
}
