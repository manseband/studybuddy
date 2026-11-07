package studybuddy.agent;

import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import studybuddy.domain.CreatedItem;

/**
 * What a tool call produced.
 *
 * @param content what goes back to the model, as JSON
 * @param summary a short line for the activity list and trace
 * @param invalid true if the tool was not run because the call itself was wrong
 * @param created items made by this call
 */
public record ToolResult(boolean success, String content, String summary, boolean invalid, List<CreatedItem> created) {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    public ToolResult {
        created = List.copyOf(created);
    }

    public static ToolResult ok(String summary, Object data) {
        return ok(summary, data, List.of());
    }

    public static ToolResult ok(String summary, Object data, List<CreatedItem> created) {
        return new ToolResult(true, toJson(data), summary, false, created);
    }

    /** The tool ran and failed; the model must tell the student what could not be done. */
    public static ToolResult failed(String message) {
        return new ToolResult(false, toJson(Map.of("error", message)), message, false, List.of());
    }

    /** The call had an unknown tool or bad arguments, so nothing was run; the model can correct it. */
    public static ToolResult invalid(String message) {
        return new ToolResult(false, toJson(Map.of("error", message)), message, true, List.of());
    }

    private static String toJson(Object data) {
        try {
            return MAPPER.writeValueAsString(data);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Could not convert a tool result to JSON", e);
        }
    }
}
