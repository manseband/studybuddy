package studybuddy.presentation.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import studybuddy.ai.LLMException;
import studybuddy.application.StudyAgentFacade;
import studybuddy.domain.Concept;
import studybuddy.domain.Material;

class WeakConceptsCommandTest {

    @TempDir
    Path dir;

    private CliTestApp app;
    private final ObjectMapper mapper = new ObjectMapper();
    private long courseId;
    private Material material;

    @BeforeEach
    void setUp() throws IOException {
        app = new CliTestApp(dir.resolve("mastery.db"));
        StudyAgentFacade facade = app.facade();
        courseId = facade.createCourse("EECS3311", "").getId();
        material = facade.uploadMaterial(courseId, Files.writeString(dir.resolve("patterns.txt"),
                "The Facade pattern gives one simple interface to a whole subsystem. ".repeat(20)));
    }

    @AfterEach
    void tearDown() {
        app.close();
    }

    private void addFacadeConcept() {
        app.facade().saveConcepts(material.getId(), List.of(new Concept(0, courseId, "Facade", "Hides a subsystem.", 3)));
    }

    /** Creates a true/false quiz with the given concept tag and answers every question "false" (wrong). */
    private void failQuizOn(String conceptJson) throws IOException {
        StringBuilder questions = new StringBuilder();
        for (int i = 0; i < 3; i++) {
            questions.append(i == 0 ? "" : ",").append("{\"type\": \"tf\", \"stem\": \"Statement ").append(i)
                    .append("\", \"answer\": true, \"excerpt\": 1, \"page\": 1, \"concept\": ").append(conceptJson)
                    .append("}");
        }
        app.llm.enqueueText("{\"questions\": [" + questions + "]}");
        assertEquals(0, app.run("quiz", "generate", "--course", "EECS3311", "--count", "3", "--types", "tf", "--json"),
                app.output);
        JsonNode quiz = mapper.readTree(app.output);
        StringBuilder answers = new StringBuilder();
        for (JsonNode question : quiz.get("questions")) {
            answers.append(answers.isEmpty() ? "" : ",").append('"').append(question.get("id").asText())
                    .append("\": \"false\"");
        }
        Path file = Files.writeString(dir.resolve("answers.json"),
                "{\"quizId\": " + quiz.get("id").asText() + ", \"answers\": {" + answers + "}}");
        assertEquals(0, app.run("quiz", "grade", "--attempt", file.toString()), app.output);
    }

    @Test
    void withoutConceptsItSaysToExtractThem() {
        assertEquals(0, app.run("weak-concepts", "--course", "EECS3311"));

        assertTrue(app.output.contains("concepts extract"), app.output);
    }

    @Test
    void withoutEvidenceItSuggestsAQuiz() {
        addFacadeConcept();

        assertEquals(0, app.run("weak-concepts", "--course", "EECS3311"));

        assertTrue(app.output.contains("No evidence yet"), app.output);
    }

    @Test
    void untaggedQuizGivesNoEvidence() throws IOException {
        addFacadeConcept();
        failQuizOn("null");

        assertEquals(0, app.run("weak-concepts", "--course", "EECS3311"));

        assertTrue(app.output.contains("No evidence yet"), app.output);
    }

    @Test
    void failedTaggedQuizMakesTheConceptWeak() throws IOException {
        addFacadeConcept();
        failQuizOn("\"Facade\"");

        assertEquals(0, app.run("weak-concepts", "--course", "EECS3311"));
        assertTrue(app.output.contains("Weak"), app.output);
        assertTrue(app.output.contains("3 answer(s)"), app.output);

        assertEquals(0, app.run("weak-concepts", "--course", "EECS3311", "--json"));
        JsonNode concept = mapper.readTree(app.output).get("concepts").get(0);
        assertEquals("Facade", concept.get("concept").asText());
        assertTrue(concept.get("weak").asBoolean());
        assertFalse(concept.get("insufficientData").asBoolean());
        assertEquals(0.0, concept.get("score").asDouble());
    }

    @Test
    void diagnoseAddsTheAnalysis() throws IOException {
        addFacadeConcept();
        failQuizOn("\"Facade\"");
        app.llm.enqueueText("{\"patterns\": [\"You answer false too often.\"], \"actions\": [\"Reread the notes.\"]}");

        assertEquals(0, app.run("weak-concepts", "--course", "EECS3311", "--diagnose"));

        assertTrue(app.output.contains("Weak"), app.output);
        assertTrue(app.output.contains("You answer false too often."), app.output);
    }

    @Test
    void failedDiagnosisStillShowsTheScores() throws IOException {
        addFacadeConcept();
        failQuizOn("\"Facade\"");
        app.llm.enqueueFailure(new LLMException("rate limited", true));

        assertEquals(0, app.run("weak-concepts", "--course", "EECS3311", "--diagnose", "--json"));

        JsonNode json = mapper.readTree(app.output);
        assertTrue(json.get("concepts").get(0).get("weak").asBoolean());
        assertTrue(json.get("diagnosisError").asText().contains("rate limited"));
    }
}
