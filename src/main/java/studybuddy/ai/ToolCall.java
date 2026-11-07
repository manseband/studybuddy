package studybuddy.ai;

import java.util.Map;
import java.util.stream.Collectors;

/** A tool call requested by the model: the tool name and its arguments parsed from JSON. */
public record ToolCall(String id, String name, Map<String, Object> arguments) {

    /** An argument the model set to null counts as left out. */
    public ToolCall {
        arguments = arguments == null ? Map.of() : arguments.entrySet().stream()
                .filter(argument -> argument.getValue() != null)
                .collect(Collectors.toUnmodifiableMap(Map.Entry::getKey, Map.Entry::getValue));
    }
}
