package studybuddy.presentation.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

class ConceptsCommandTest {

    private static final String REPLY = """
            {"concepts": [
              {"name": "Facade", "definition": "One simple interface to a subsystem.", "pages": [1], "importance": 4,
               "prerequisites": ["Subsystem"]},
              {"name": "Subsystem", "definition": "A group of related classes.", "pages": [1], "importance": 2}]}""";

    @TempDir
    Path dir;

    private CliTestApp app;

    @BeforeEach
    void setUp() throws IOException {
        app = new CliTestApp(dir.resolve("cli.db"));
        app.run("course", "create", "EECS3311");
        app.run("material", "add", "--course", "EECS3311",
                Files.writeString(dir.resolve("notes.txt"), "The Facade pattern hides a subsystem.").toString());
    }

    @AfterEach
    void tearDown() {
        app.close();
    }

    @Test
    void previewDoesNotSaveButExtractDoes() {
        app.llm.enqueueText(REPLY).enqueueText(REPLY);

        assertEquals(0, app.run("concepts", "extract", "--material", "1", "--preview"));
        assertTrue(app.output.contains("new    ****  Facade"), app.output);
        assertTrue(app.output.contains("needs: Subsystem"));
        assertTrue(app.output.endsWith("2 concept(s) found (preview, not saved)."));
        app.run("concepts", "list", "--course", "EECS3311");
        assertTrue(app.output.startsWith("No concepts yet"));

        assertEquals(0, app.run("concepts", "extract", "--material", "1"));
        assertTrue(app.output.endsWith("2 concept(s) saved."));
        app.run("concepts", "show", "1");
        assertTrue(app.output.contains("appears in: notes.txt"), app.output);
    }

    @Test
    void editMergeDeleteAndJson() throws IOException {
        app.llm.enqueueText(REPLY);
        app.run("concepts", "extract", "--material", "1");

        assertEquals(0, app.run("concepts", "edit", "2", "--importance", "5", "--json"));
        Map<String, Object> edited = new ObjectMapper().readValue(app.output, new TypeReference<>() { });
        assertEquals(5, edited.get("importance"));

        assertEquals(0, app.run("concepts", "merge", "2", "1"));
        assertEquals(0, app.run("concepts", "list", "--course", "EECS3311", "--json"));
        List<Map<String, Object>> concepts = new ObjectMapper().readValue(app.output, new TypeReference<>() { });
        assertEquals(1, concepts.size());
        assertEquals(5, concepts.get(0).get("importance"));

        assertEquals(0, app.run("concepts", "delete", "1"));
        assertEquals(1, app.run("concepts", "show", "1"));
        assertTrue(app.output.contains("No concept with id 1"));
    }

    @Test
    void noConceptsFound() {
        app.llm.enqueueText("{\"concepts\": []}");
        assertEquals(0, app.run("concepts", "extract", "--material", "1"));
        assertTrue(app.output.contains("may be too short"));
    }
}
