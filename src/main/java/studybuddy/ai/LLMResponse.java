package studybuddy.ai;

import java.util.List;

/**
 * A provider-independent model reply: text and/or tool calls, plus token usage for cost tracking.
 *
 * @param providerState opaque data the provider needs back with this reply's tool calls, or null. Gemini 3 rejects
 *                      a follow-up request without its "thought signature". Only the adapter looks inside it.
 */
public record LLMResponse(String text, List<ToolCall> toolCalls, int inputTokens, int outputTokens,
                          String providerState) {

    public LLMResponse {
        text = text == null ? "" : text;
        toolCalls = toolCalls == null ? List.of() : List.copyOf(toolCalls);
    }

    public static LLMResponse text(String text) {
        return new LLMResponse(text, List.of(), 0, 0, null);
    }

    public static LLMResponse toolCalls(ToolCall... calls) {
        return new LLMResponse("", List.of(calls), 0, 0, null);
    }

    public boolean hasToolCalls() {
        return !toolCalls.isEmpty();
    }
}
