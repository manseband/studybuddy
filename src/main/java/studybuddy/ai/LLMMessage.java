package studybuddy.ai;

import java.util.List;

/**
 * One message in a conversation with the model.
 * ASSISTANT messages may carry tool calls; TOOL messages carry the result of one tool call.
 */
public record LLMMessage(Role role, String text, List<ToolCall> toolCalls, String toolCallId, String toolName) {

    public enum Role {
        USER,
        ASSISTANT,
        TOOL
    }

    public LLMMessage {
        text = text == null ? "" : text;
        toolCalls = toolCalls == null ? List.of() : List.copyOf(toolCalls);
    }

    public static LLMMessage user(String text) {
        return new LLMMessage(Role.USER, text, List.of(), null, null);
    }

    public static LLMMessage assistant(String text) {
        return new LLMMessage(Role.ASSISTANT, text, List.of(), null, null);
    }

    public static LLMMessage assistantToolCalls(String text, List<ToolCall> calls) {
        return new LLMMessage(Role.ASSISTANT, text, calls, null, null);
    }

    public static LLMMessage toolResult(ToolCall call, String result) {
        return new LLMMessage(Role.TOOL, result, List.of(), call.id(), call.name());
    }
}
