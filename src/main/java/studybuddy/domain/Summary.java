package studybuddy.domain;

import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Collectors;

/** A saved material summary (F02). fromPage/toPage are null when the whole material was summarized. */
public record Summary(long id, long materialId, SummaryLength length, Integer fromPage, Integer toPage,
                      String overview, List<KeyPoint> keyPoints, List<String> keyTerms, LocalDateTime createdAt) {

    public Summary {
        keyPoints = List.copyOf(keyPoints);
        keyTerms = List.copyOf(keyTerms);
    }

    public Summary withId(long newId) {
        return new Summary(newId, materialId, length, fromPage, toPage, overview, keyPoints, keyTerms, createdAt);
    }

    /** Markdown used by "Export as Markdown". */
    public String toMarkdown(String materialTitle) {
        StringBuilder md = new StringBuilder();
        md.append("# Summary: ").append(materialTitle).append("\n\n");
        if (fromPage != null && toPage != null) {
            md.append("_Pages ").append(fromPage).append("-").append(toPage).append("_\n\n");
        }
        md.append("## Overview\n\n").append(overview).append("\n\n## Key points\n\n");
        for (KeyPoint point : keyPoints) {
            md.append("- ").append(point.text());
            if (!point.pages().isEmpty()) {
                md.append(" (p. ")
                  .append(point.pages().stream().map(String::valueOf).collect(Collectors.joining(", ")))
                  .append(')');
            }
            if (!point.sourceVerified()) {
                md.append(" _[source unverified]_");
            }
            md.append('\n');
        }
        if (!keyTerms.isEmpty()) {
            md.append("\n## Key terms\n\n").append(String.join(", ", keyTerms)).append('\n');
        }
        return md.toString();
    }
}
