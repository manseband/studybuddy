package studybuddy.domain;

import java.util.List;

/**
 * One key point of a summary (F02).
 * sourceVerified is false when the page references fell outside the selected range and were removed.
 */
public record KeyPoint(String text, List<Integer> pages, boolean sourceVerified) {

    public KeyPoint {
        pages = List.copyOf(pages);
    }
}
