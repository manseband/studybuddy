package studybuddy.ai;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.encryption.InvalidPasswordException;
import org.apache.pdfbox.text.PDFTextStripper;

/**
 * Extracts text from course materials page by page (F01 steps 2-3).
 * PDFs are read with Apache PDFBox; TXT and Markdown files are read as plain text, with a form-feed
 * character marking a page break (otherwise the whole file is page 1).
 * Whitespace is normalized, words hyphenated across line breaks are joined, and header/footer lines that
 * repeat on most pages (e.g. a course title or "Page 3") are removed.
 */
public class DocumentParser {

    public static final Set<String> SUPPORTED_TYPES = Set.of("pdf", "txt", "md");

    /** Minimum number of pages before repeated first/last lines are treated as headers/footers. */
    private static final int MIN_PAGES_FOR_HEADER_DETECTION = 3;

    public boolean isSupported(Path file) {
        return SUPPORTED_TYPES.contains(fileType(file));
    }

    /** Lower-case extension without the dot, e.g. "pdf"; "" if there is none. */
    public static String fileType(Path file) {
        String name = file.getFileName().toString();
        int dot = name.lastIndexOf('.');
        return dot < 0 ? "" : name.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    /**
     * Returns the cleaned text of each page, in order. A page without extractable text (e.g. a scanned
     * image) is returned as an empty string.
     *
     * @throws DocumentParseException if the file is encrypted, corrupted or cannot be read
     */
    public List<String> extractPages(Path file) {
        if (!isSupported(file)) {
            throw new DocumentParseException("Unsupported file type: " + file.getFileName()
                    + " (accepted: PDF, TXT, MD)");
        }
        List<String> raw = fileType(file).equals("pdf") ? readPdf(file) : readText(file);
        List<String> pages = raw.stream().map(DocumentParser::normalize).toList();
        return removeRepeatedHeadersAndFooters(pages);
    }

    private static List<String> readPdf(Path file) {
        try (PDDocument document = Loader.loadPDF(file.toFile())) {
            if (document.isEncrypted()) {
                throw new DocumentParseException("The PDF is encrypted and cannot be read: " + file.getFileName());
            }
            PDFTextStripper stripper = new PDFTextStripper();
            List<String> pages = new ArrayList<>();
            for (int page = 1; page <= document.getNumberOfPages(); page++) {
                stripper.setStartPage(page);
                stripper.setEndPage(page);
                pages.add(stripper.getText(document));
            }
            return pages;
        } catch (InvalidPasswordException e) {
            throw new DocumentParseException("The PDF is password-protected and cannot be read: "
                    + file.getFileName(), e);
        } catch (IOException e) {
            throw new DocumentParseException("The PDF is corrupted or unreadable: " + file.getFileName(), e);
        }
    }

    private static List<String> readText(Path file) {
        try {
            String text = Files.readString(file, StandardCharsets.UTF_8);
            return List.of(text.split("\f", -1));
        } catch (IOException e) {
            throw new DocumentParseException("Could not read " + file.getFileName() + ": " + e.getMessage(), e);
        }
    }

    /** Joins hyphenated line breaks, collapses spaces and tabs, trims lines and drops extra blank lines. */
    static String normalize(String page) {
        String text = page.replace("\r\n", "\n").replace('\r', '\n').replace(' ', ' ');
        text = text.replaceAll("(\\p{L})-\n(\\p{Ll})", "$1$2");
        text = text.replaceAll("[ \\t\\x0B]+", " ");
        StringBuilder out = new StringBuilder();
        int blankRun = 0;
        for (String line : text.split("\n")) {
            String trimmed = line.strip();
            if (trimmed.isEmpty()) {
                blankRun++;
                if (blankRun == 1 && !out.isEmpty()) {
                    out.append('\n');
                }
                continue;
            }
            blankRun = 0;
            if (!out.isEmpty()) {
                out.append('\n');
            }
            out.append(trimmed);
        }
        return out.toString().strip();
    }

    /**
     * Removes first or last lines that repeat on at least half of the pages. Digits are ignored when
     * comparing, so "Page 3" and "Page 4" count as the same footer.
     */
    static List<String> removeRepeatedHeadersAndFooters(List<String> pages) {
        long nonEmpty = pages.stream().filter(p -> !p.isBlank()).count();
        if (nonEmpty < MIN_PAGES_FOR_HEADER_DETECTION) {
            return pages;
        }
        Map<String, Integer> firstLines = new HashMap<>();
        Map<String, Integer> lastLines = new HashMap<>();
        for (String page : pages) {
            List<String> lines = page.lines().toList();
            if (lines.size() < 2) {
                continue;
            }
            firstLines.merge(key(lines.get(0)), 1, Integer::sum);
            lastLines.merge(key(lines.get(lines.size() - 1)), 1, Integer::sum);
        }
        long threshold = Math.max(2, (nonEmpty + 1) / 2);
        List<String> cleaned = new ArrayList<>();
        for (String page : pages) {
            List<String> lines = new ArrayList<>(page.lines().toList());
            if (lines.size() >= 2 && firstLines.getOrDefault(key(lines.get(0)), 0) >= threshold) {
                lines.remove(0);
            }
            if (lines.size() >= 2 && lastLines.getOrDefault(key(lines.get(lines.size() - 1)), 0) >= threshold) {
                lines.remove(lines.size() - 1);
            }
            cleaned.add(String.join("\n", lines).strip());
        }
        return cleaned;
    }

    private static String key(String line) {
        return line.toLowerCase(Locale.ROOT).replaceAll("\\d+", "#").strip();
    }
}
