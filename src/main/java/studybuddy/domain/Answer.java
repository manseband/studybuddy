package studybuddy.domain;

import java.util.List;

/**
 * Result of a question over course materials (F04).
 *
 * @param grounded            true if the answer comes from course excerpts; false for a
 *                            "Not from your course materials" general-knowledge answer or a "not covered" reply
 * @param partiallyUnverified true if the model cited excerpts that were not provided and those citations were removed
 */
public record Answer(String text, List<Citation> citations, boolean grounded, boolean partiallyUnverified) {

    public Answer {
        citations = List.copyOf(citations);
    }

    public static Answer notCovered(String text) {
        return new Answer(text, List.of(), false, false);
    }

    public Answer withCitations(List<Citation> validCitations, boolean someRemoved) {
        return new Answer(text, validCitations, grounded, partiallyUnverified || someRemoved);
    }
}
