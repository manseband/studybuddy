package studybuddy.domain;

import java.util.Locale;

/** File formats of the exported progress report (F13). */
public enum ReportFormat {
    MARKDOWN("md"),
    CSV("csv");

    private final String extension;

    ReportFormat(String extension) {
        this.extension = extension;
    }

    public String extension() {
        return extension;
    }

    public static ReportFormat fromString(String value) {
        return switch (value == null ? "" : value.trim().toLowerCase(Locale.ROOT)) {
            case "md", "markdown" -> MARKDOWN;
            case "csv" -> CSV;
            default -> throw new IllegalArgumentException("Unknown report format '" + value
                    + "' (expected markdown or csv)");
        };
    }
}
