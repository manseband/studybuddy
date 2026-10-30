package studybuddy.domain;

import java.util.List;

/**
 * Result of a question over course materials (F04).
 *
 * @param grounded            true if the answer comes from course excerpts
 * @param generalKnowledge    true for an answer from the model's general knowledge, shown with a
 *                            "Not from your course materials" label (only when the student allowed it)
 * @param partiallyUnverified true if the model cited excerpts that were not provided and those citations were removed
 */
public record Answer(String text, List<Citation> citations, boolean grounded, boolean generalKnowledge,
                     boolean partiallyUnverified) {

    public Answer {
        citations = List.copyOf(citations);
    }

    /** The materials do not cover the question and general knowledge was not allowed. */
    public static Answer notCovered(String text) {
        return new Answer(text, List.of(), false, false, false);
    }

    public static Answer generalKnowledge(String text) {
        return new Answer(text, List.of(), false, true, false);
    }

    public Answer withCitations(List<Citation> validCitations, boolean someRemoved) {
        return new Answer(text, validCitations, grounded, generalKnowledge, partiallyUnverified || someRemoved);
    }
}
