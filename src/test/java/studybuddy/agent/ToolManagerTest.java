package studybuddy.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import studybuddy.ai.ToolCall;
import studybuddy.domain.TraceStep;

class ToolManagerTest {

    /** A tool that records whether it ran. */
    private static final class SampleTool implements AgentTool {
        boolean ran;
        RuntimeException failure;

        @Override
        public String getName() {
            return "sample";
        }

        @Override
        public String getDescription() {
            return "A sample tool";
        }

        @Override
        public String getParameterSchema() {
            return """
                    {"type": "object",
                     "properties": {
                       "id": {"type": "integer"},
                       "count": {"type": "integer", "minimum": 1, "maximum": 5},
                       "name": {"type": "string"},
                       "flag": {"type": "boolean"},
                       "level": {"type": "string", "enum": ["easy", "hard"]},
                       "ids": {"type": "array", "items": {"type": "integer"}},
                       "kinds": {"type": "array", "items": {"type": "string", "enum": ["a", "b"]}}},
                     "required": ["id"]}""";
        }

        @Override
        public String describe(Map<String, Object> args) {
            return "Running sample";
        }

        @Override
        public ToolResult execute(Map<String, Object> args) {
            ran = true;
            if (failure != null) {
                throw failure;
            }
            return ToolResult.ok("It worked", Map.of("ok", true));
        }
    }

    private SampleTool tool;
    private ToolManager manager;
    private AgentTrace trace;

    @BeforeEach
    void setUp() {
        tool = new SampleTool();
        manager = new ToolManager();
        manager.register(tool);
        trace = new AgentTrace(step -> { });
    }

    private ToolResult call(Map<String, Object> args) {
        return manager.execute(new ToolCall("1", "sample", args), trace);
    }

    private void assertRejected(String expected, Map<String, Object> args) {
        tool.ran = false;
        ToolResult result = call(args);
        assertTrue(result.invalid());
        assertFalse(result.success());
        assertTrue(result.content().contains(expected), result.content());
        assertFalse(tool.ran);
    }

    @Test
    void describesItsTools() {
        assertEquals("sample", manager.getToolSpecs().get(0).name());
        assertEquals("A sample tool", manager.getToolSpecs().get(0).description());
    }

    @Test
    void runsAValidCallAndRecordsIt() {
        ToolResult result = call(Map.of("id", 7, "count", 5, "name", "x", "flag", true, "level", "hard",
                "ids", List.of(1, 2), "kinds", List.of("a")));

        assertTrue(result.success());
        assertTrue(tool.ran);
        TraceStep step = trace.getSteps().get(0);
        assertEquals("Running sample", step.description());
        assertEquals("It worked", step.result());
        assertEquals(TraceStep.Status.SUCCEEDED, step.status());
        assertEquals(1, step.number());
        assertEquals(7, step.arguments().get("id"));
    }

    @Test
    void acceptsWholeNumbersSentAsDecimals() {
        assertTrue(call(Map.of("id", 7.0)).success());
    }

    @Test
    void rejectsCallsThatDoNotMatchTheSchema() {
        assertRejected("\\\"id\\\" is required", Map.of());
        assertRejected("is not an argument", Map.of("id", 1, "other", 2));
        assertRejected("must be a whole number", Map.of("id", "seven"));
        assertRejected("must be a whole number", Map.of("id", 1.5));
        assertRejected("between 1 and 5", Map.of("id", 1, "count", 0));
        assertRejected("between 1 and 5", Map.of("id", 1, "count", 6));
        assertRejected("must be text", Map.of("id", 1, "name", 3));
        assertRejected("must be true or false", Map.of("id", 1, "flag", "yes"));
        assertRejected("must be one of", Map.of("id", 1, "level", "medium"));
        assertRejected("must be a list", Map.of("id", 1, "ids", 3));
        assertRejected("must be a whole number", Map.of("id", 1, "ids", List.of("x")));
        assertRejected("must be one of", Map.of("id", 1, "kinds", List.of("c")));
        assertRejected("must not contain null", Map.of("id", 1, "ids", Arrays.asList(1, null)));
    }

    @Test
    void anInvalidCallIsTracedAsAFailedStep() {
        call(Map.of());

        assertEquals(TraceStep.Status.FAILED, trace.getSteps().get(0).status());
    }

    @Test
    void anUnknownToolListsTheAvailableOnes() {
        ToolResult result = manager.execute(new ToolCall("1", "missing", Map.of()), trace);

        assertTrue(result.invalid());
        assertTrue(result.content().contains("sample"), result.content());
        assertEquals(TraceStep.Status.FAILED, trace.getSteps().get(0).status());
    }

    @Test
    void aToolThatThrowsBecomesAFailedResult() {
        tool.failure = new IllegalStateException("No text");

        ToolResult result = call(Map.of("id", 1));

        assertFalse(result.success());
        assertFalse(result.invalid());
        assertTrue(result.content().contains("No text"));
        assertEquals("No text", trace.getSteps().get(0).result());
    }

    @Test
    void anArgumentSetToNullCountsAsLeftOut() {
        Map<String, Object> args = new HashMap<>();
        args.put("id", 1);
        args.put("name", null);

        assertTrue(call(args).success());
    }
}
