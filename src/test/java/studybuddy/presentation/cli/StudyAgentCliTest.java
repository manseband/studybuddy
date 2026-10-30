package studybuddy.presentation.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import studybuddy.application.ApplicationFactory;
import studybuddy.config.AppConfig;

class StudyAgentCliTest {

    @TempDir
    Path dir;

    private String lastOutput;

    private int run(String... args) {
        AppConfig config = AppConfig.load(dir.resolve("no.env"),
                Map.of("STUDYBUDDY_DB_PATH", dir.resolve("cli.db").toString()));
        StringWriter buffer = new StringWriter();
        int code = new StudyAgentCli(() -> ApplicationFactory.create(config)).execute(args, new PrintWriter(buffer));
        lastOutput = buffer.toString().strip();
        return code;
    }

    @Test
    void createListAndAddMaterialAsText() throws IOException {
        assertEquals(0, run("course", "create", "EECS3311", "--code", "Software Design", "--exam", "2026-12-10"));
        assertTrue(lastOutput.startsWith("Created course #1: EECS3311 (Software Design), exam on 2026-12-10"));

        Path notes = Files.writeString(dir.resolve("notes.md"), "Observer pattern notes");
        assertEquals(0, run("material", "add", "--course", "eecs3311", notes.toString()));
        assertTrue(lastOutput.contains("3 words, 1 chunks"), lastOutput);

        assertEquals(0, run("material", "list", "--course", "1"));
        assertTrue(lastOutput.contains("notes.md"));
        assertTrue(lastOutput.contains("Indexed"));
    }

    @Test
    void jsonOutputWorksOnEverySubcommand() throws IOException {
        run("course", "create", "EECS3311");
        assertEquals(0, run("course", "list", "--json"));
        List<Map<String, Object>> courses = new ObjectMapper().readValue(lastOutput, new TypeReference<>() { });
        assertEquals("EECS3311", courses.get(0).get("name"));

        Path notes = Files.writeString(dir.resolve("notes.txt"), "Strategy pattern");
        assertEquals(0, run("--json", "material", "add", "--course", "EECS3311", notes.toString()));
        Map<String, Object> material = new ObjectMapper().readValue(lastOutput, new TypeReference<>() { });
        assertEquals(1, material.get("chunks"));
    }

    @Test
    void errorsAreReportedWithExitCodes() throws IOException {
        assertEquals(1, run("material", "list", "--course", "missing", "--json"));
        assertTrue(lastOutput.contains("\"error\""));

        run("course", "create", "EECS3311");
        Path notes = Files.writeString(dir.resolve("a.txt"), "Facade");
        run("material", "add", "--course", "EECS3311", notes.toString());
        assertEquals(3, run("material", "add", "--course", "EECS3311", notes.toString()));
        assertTrue(lastOutput.contains("--replace"));
        assertEquals(0, run("material", "add", "--course", "EECS3311", "--replace", notes.toString()));

        Path pptx = Files.writeString(dir.resolve("slides.pptx"), "x");
        assertEquals(1, run("material", "add", "--course", "EECS3311", pptx.toString()));
        assertTrue(lastOutput.contains("Accepted types"));
    }

    @Test
    void courseDeletionNeedsConfirmation() {
        run("course", "create", "EECS3311");
        assertEquals(2, run("course", "delete", "EECS3311"));
        assertTrue(lastOutput.contains("--yes"));
        assertEquals(0, run("course", "delete", "EECS3311", "--yes"));
        run("course", "list");
        assertTrue(lastOutput.startsWith("No courses yet"));
    }
}
