package studybuddy.application;

import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import studybuddy.domain.ActivityRecord;
import studybuddy.domain.ConceptMastery;
import studybuddy.domain.ProgressReport;

/** Turns a progress report into the text of an exported file (F13). */
final class ProgressReportWriter {

    private static final List<String> MASTERY_HEADER = List.of("Concept", "Mastery", "Evidence", "Trend", "Status");
    private static final List<String> HISTORY_HEADER = List.of("Date", "Activity", "Details", "Result");

    private ProgressReportWriter() {
    }

    static String toMarkdown(ProgressReport report) {
        StringBuilder text = new StringBuilder("# Progress report: ").append(report.courseName()).append("\n\n");
        text.append("- Average score: ").append(report.averageScoreText()).append('\n');
        text.append("- Current streak: ").append(report.streakText()).append("\n\n");
        text.append("## Concept mastery\n\n");
        appendMarkdownTable(text, MASTERY_HEADER, masteryRows(report), "No concepts yet.");
        text.append("\n## Study history\n\n");
        appendMarkdownTable(text, HISTORY_HEADER, historyRows(report), "No activity yet.");
        return text.toString();
    }

    private static void appendMarkdownTable(StringBuilder text, List<String> header, List<List<String>> rows,
                                            String whenEmpty) {
        if (rows.isEmpty()) {
            text.append(whenEmpty).append('\n');
            return;
        }
        text.append(markdownRow(header)).append('\n');
        text.append(markdownRow(header.stream().map(column -> "---").toList())).append('\n');
        rows.forEach(row -> text.append(markdownRow(row)).append('\n'));
    }

    private static String markdownRow(List<String> cells) {
        return cells.stream().map(cell -> cell.replace("|", "\\|")).collect(Collectors.joining(" | ", "| ", " |"));
    }

    /** Three blocks separated by a blank line: the summary, the mastery table and the history table. */
    static String toCsv(ProgressReport report) {
        List<List<String>> summary = List.of(List.of("Course", report.courseName()),
                List.of("Average score", report.averageScoreText()), List.of("Current streak", report.streakText()));
        return Stream.of(csvBlock(summary), csvBlock(withHeader(MASTERY_HEADER, masteryRows(report))),
                csvBlock(withHeader(HISTORY_HEADER, historyRows(report)))).collect(Collectors.joining("\n"));
    }

    private static List<List<String>> withHeader(List<String> header, List<List<String>> rows) {
        return Stream.concat(Stream.of(header), rows.stream()).toList();
    }

    private static String csvBlock(List<List<String>> rows) {
        return rows.stream()
                .map(row -> row.stream().map(ProgressReportWriter::csvCell).collect(Collectors.joining(",")) + "\n")
                .collect(Collectors.joining());
    }

    /** Quotes a cell if it contains a character that means something in CSV. */
    private static String csvCell(String cell) {
        if (cell.contains(",") || cell.contains("\"") || cell.contains("\n")) {
            return '"' + cell.replace("\"", "\"\"") + '"';
        }
        return cell;
    }

    private static List<List<String>> masteryRows(ProgressReport report) {
        return report.mastery().stream().map(ProgressReportWriter::masteryRow).toList();
    }

    private static List<String> masteryRow(ConceptMastery m) {
        String score = m.insufficientData() ? "-" : String.format("%.0f", m.score());
        return List.of(m.conceptName(), score, String.valueOf(m.evidenceCount()), m.trend().name().toLowerCase(),
                m.statusLabel());
    }

    private static List<List<String>> historyRows(ProgressReport report) {
        return report.history().stream().map(ProgressReportWriter::historyRow).toList();
    }

    private static List<String> historyRow(ActivityRecord r) {
        return List.of(r.dateLabel(), r.activity(), r.details(), r.result());
    }
}
