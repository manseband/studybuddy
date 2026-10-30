package studybuddy.ai;

import java.util.List;

/** A provider-independent model reply: text and/or tool calls, plus token usage for cost tracking. */
public record LLMResponse(String text, List<ToolCall> toolCalls, int inputTokens, int outputTokens) {

    public LLMResponse {
        text = text == null ? "" : text;
        toolCalls = toolCalls == null ? List.of() : List.copyOf(toolCalls);
    }

    public static LLMResponse text(String text) {
        return new LLMResponse(text, List.of(), 0, 0);
    }

    public static LLMResponse toolCalls(ToolCall... calls) {
        return new LLMResponse("", List.of(calls), 0, 0);
    }

    public boolean hasToolCalls() {
        return !toolCalls.isEmpty();
    }
}
