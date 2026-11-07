package studybuddy.agent;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import studybuddy.ai.ToolCall;
import studybuddy.ai.ToolSpec;

/**
 * Command invoker: holds the tools, checks each call against the tool's schema, runs it and records the
 * step. A bad call is never run; its error goes back to the model so it can correct itself.
 */
public class ToolManager {

    private final Map<String, AgentTool> tools = new LinkedHashMap<>();
    private final ObjectMapper mapper = new ObjectMapper();

    public void register(AgentTool tool) {
        tools.put(tool.getName(), tool);
    }

    public List<ToolSpec> getToolSpecs() {
        return tools.values().stream()
                .map(tool -> new ToolSpec(tool.getName(), tool.getDescription(), tool.getParameterSchema()))
                .toList();
    }

    /** Runs the call and records it in the trace. Never throws because of the tool or its arguments. */
    public ToolResult execute(ToolCall call, AgentTrace trace) {
        AgentTool tool = tools.get(call.name());
        if (tool == null) {
            int number = trace.startStep(call.name(), "Unknown tool " + call.name(), call.arguments());
            ToolResult result = ToolResult.invalid("There is no tool named \"" + call.name() + "\". Available tools: "
                    + String.join(", ", tools.keySet()) + ".");
            trace.finishStep(number, result.summary(), false, 0);
            return result;
        }
        String problem = validateArgs(tool, call.arguments());
        if (problem != null) {
            int number = trace.startStep(call.name(), "Invalid call to " + call.name(), call.arguments());
            ToolResult result = ToolResult.invalid("Invalid arguments: " + problem);
            trace.finishStep(number, result.summary(), false, 0);
            return result;
        }
        int number = trace.startStep(call.name(), tool.describe(call.arguments()), call.arguments());
        long start = System.nanoTime();
        ToolResult result;
        try {
            result = tool.execute(call.arguments());
        } catch (RuntimeException e) {
            result = ToolResult.failed(e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
        }
        trace.finishStep(number, result.summary(), result.success(), (System.nanoTime() - start) / 1_000_000);
        return result;
    }

    /** @return what is wrong with the arguments, or null if they match the tool's schema */
    private String validateArgs(AgentTool tool, Map<String, Object> args) {
        JsonNode schema;
        try {
            schema = mapper.readTree(tool.getParameterSchema());
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Tool " + tool.getName() + " has an invalid schema", e);
        }
        for (JsonNode required : schema.path("required")) {
            if (!args.containsKey(required.asText())) {
                return "\"" + required.asText() + "\" is required.";
            }
        }
        JsonNode properties = schema.path("properties");
        for (Map.Entry<String, Object> arg : args.entrySet()) {
            if (!properties.has(arg.getKey())) {
                return "\"" + arg.getKey() + "\" is not an argument of " + tool.getName() + ".";
            }
            String problem = checkValue(arg.getKey(), arg.getValue(), properties.get(arg.getKey()));
            if (problem != null) {
                return problem;
            }
        }
        return null;
    }

    private static String checkValue(String name, Object value, JsonNode schema) {
        if (schema.has("enum")) {
            for (JsonNode allowed : schema.get("enum")) {
                if (allowed.asText().equals(String.valueOf(value))) {
                    return null;
                }
            }
            return "\"" + name + "\" must be one of " + schema.get("enum") + ".";
        }
        return switch (schema.path("type").asText()) {
            case "string" -> value instanceof String ? null : "\"" + name + "\" must be text.";
            case "boolean" -> value instanceof Boolean ? null : "\"" + name + "\" must be true or false.";
            case "integer" -> checkInteger(name, value, schema);
            case "array" -> checkArray(name, value, schema);
            default -> null;
        };
    }

    private static String checkInteger(String name, Object value, JsonNode schema) {
        if (!(value instanceof Number number) || number.doubleValue() != Math.rint(number.doubleValue())) {
            return "\"" + name + "\" must be a whole number.";
        }
        if (schema.has("minimum") && number.doubleValue() < schema.get("minimum").asDouble()
                || schema.has("maximum") && number.doubleValue() > schema.get("maximum").asDouble()) {
            return "\"" + name + "\" must be between " + schema.path("minimum").asText() + " and "
                    + schema.path("maximum").asText() + ".";
        }
        return null;
    }

    private static String checkArray(String name, Object value, JsonNode schema) {
        if (!(value instanceof List<?> items)) {
            return "\"" + name + "\" must be a list.";
        }
        for (Object item : items) {
            String problem = item == null ? "\"" + name + "\" must not contain null."
                    : checkValue(name, item, schema.path("items"));
            if (problem != null) {
                return problem;
            }
        }
        return null;
    }
}
