package studybuddy.ai;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.encryption.AccessPermission;
import org.apache.pdfbox.pdmodel.encryption.StandardProtectionPolicy;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DocumentParserTest {

    @TempDir
    Path dir;

    private final DocumentParser parser = new DocumentParser();

    /** Writes a PDF whose pages contain the given lines (null = blank page, as in a scanned image). */
    private Path writePdf(String name, List<List<String>> pages) throws IOException {
        Path file = dir.resolve(name);
        try (PDDocument doc = new PDDocument()) {
            PDType1Font font = new PDType1Font(Standard14Fonts.FontName.HELVETICA);
            for (List<String> lines : pages) {
                PDPage page = new PDPage();
                doc.addPage(page);
                if (lines == null) {
                    continue;
                }
                try (PDPageContentStream content = new PDPageContentStream(doc, page)) {
                    content.beginText();
                    content.setFont(font, 12);
                    content.setLeading(16);
                    content.newLineAtOffset(72, 720);
                    for (String line : lines) {
                        content.showText(line);
                        content.newLine();
                    }
                    content.endText();
                }
            }
            doc.save(file.toFile());
        }
        return file;
    }

    @Test
    void recognisesSupportedTypes() {
        assertTrue(parser.isSupported(Path.of("notes.PDF")));
        assertTrue(parser.isSupported(Path.of("readme.md")));
        assertFalse(parser.isSupported(Path.of("slides.pptx")));
        assertFalse(parser.isSupported(Path.of("noextension")));
        assertThrows(DocumentParseException.class, () -> parser.extractPages(dir.resolve("slides.pptx")));
    }

    @Test
    void extractsPdfTextPageByPageAndRemovesRepeatedHeaderAndFooter() throws IOException {
        Path pdf = writePdf("patterns.pdf", List.of(
                List.of("EECS 3311 Software Design", "The Facade pattern hides a subsystem.", "Page 1"),
                List.of("EECS 3311 Software Design", "The Adapter pattern converts interfaces.", "Page 2"),
                List.of("EECS 3311 Software Design", "The Observer pattern notifies listeners.", "Page 3")));

        List<String> pages = parser.extractPages(pdf);

        assertEquals(3, pages.size());
        assertEquals("The Facade pattern hides a subsystem.", pages.get(0));
        assertEquals("The Observer pattern notifies listeners.", pages.get(2));
    }

    @Test
    void blankPdfPagesComeBackEmpty() throws IOException {
        Path pdf = writePdf("scan.pdf", java.util.Arrays.asList(null, null));

        assertEquals(List.of("", ""), parser.extractPages(pdf));
    }

    @Test
    void encryptedPdfIsRejected() throws IOException {
        Path file = dir.resolve("locked.pdf");
        try (PDDocument doc = new PDDocument()) {
            doc.addPage(new PDPage());
            StandardProtectionPolicy policy = new StandardProtectionPolicy("owner-pw", "user-pw", new AccessPermission());
            doc.protect(policy);
            doc.save(file.toFile());
        }

        DocumentParseException e = assertThrows(DocumentParseException.class, () -> parser.extractPages(file));
        assertTrue(e.getMessage().contains("password"));
    }

    @Test
    void corruptedPdfIsRejected() throws IOException {
        Path file = Files.writeString(dir.resolve("broken.pdf"), "this is not really a pdf");

        DocumentParseException e = assertThrows(DocumentParseException.class, () -> parser.extractPages(file));
        assertTrue(e.getMessage().contains("corrupted"));
    }

    @Test
    void textFilesUseFormFeedsAsPageBreaks() throws IOException {
        Path md = Files.writeString(dir.resolve("notes.md"), "# Observer\r\n\r\n\r\nSubjects   notify\tobservers.\fSecond page");

        List<String> pages = parser.extractPages(md);

        assertEquals(List.of("# Observer\n\nSubjects notify observers.", "Second page"), pages);
    }

    @Test
    void normalizeJoinsHyphenatedWords() {
        assertEquals("an example sentence", DocumentParser.normalize("an exam-\nple sentence"));
    }
}
