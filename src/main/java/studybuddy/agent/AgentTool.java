package studybuddy.agent;

import java.util.List;
import java.util.Map;

/**
 * Command: one action the Study Coach can take. Each tool wraps an existing service, so the model only
 * decides what to do; the work is ordinary tested code. Tools may read and create data, but must never
 * delete or change existing data. {@link ToolManager} checks the arguments against the schema before
 * {@link #execute} runs.
 */
public interface AgentTool {

    String getName();

    /** Tells the model what the tool does and when to use it. */
    String getDescription();

    /** JSON Schema of the arguments. */
    String getParameterSchema();

    /** What this call is doing, for the student, e.g. "Generating 8-question quiz". */
    String describe(Map<String, Object> args);

    /** @throws RuntimeException if the work fails; ToolManager reports the message to the model */
    ToolResult execute(Map<String, Object> args);

    // ----- reading arguments that the schema has already checked -----

    static long longArg(Map<String, Object> args, String name) {
        return ((Number) args.get(name)).longValue();
    }

    static int intArg(Map<String, Object> args, String name, int defaultValue) {
        return args.get(name) instanceof Number number ? number.intValue() : defaultValue;
    }

    static String stringArg(Map<String, Object> args, String name, String defaultValue) {
        return args.get(name) instanceof String text ? text : defaultValue;
    }

    /** A list of ids, or an empty list if the argument was left out. */
    static List<Long> idsArg(Map<String, Object> args, String name) {
        if (args.get(name) instanceof List<?> values) {
            return values.stream().map(value -> ((Number) value).longValue()).toList();
        }
        return List.of();
    }

    static List<String> stringsArg(Map<String, Object> args, String name) {
        if (args.get(name) instanceof List<?> values) {
            return values.stream().map(String::valueOf).toList();
        }
        return List.of();
    }
}
