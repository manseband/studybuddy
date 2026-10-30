package studybuddy.ai;

import java.util.Map;

/** A tool call requested by the model: the tool name and its arguments parsed from JSON. */
public record ToolCall(String id, String name, Map<String, Object> arguments) {

    public ToolCall {
        arguments = arguments == null ? Map.of() : Map.copyOf(arguments);
    }
}
