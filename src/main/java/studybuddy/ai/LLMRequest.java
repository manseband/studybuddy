package studybuddy.ai;

import java.util.ArrayList;
import java.util.List;

/**
 * A provider-independent request to a chat model. Built by {@link PromptBuilder}.
 *
 * @param jsonOutput true when the caller expects a JSON object back (the model is asked for JSON mode)
 */
public record LLMRequest(String systemText, List<LLMMessage> messages, List<ToolSpec> tools, boolean jsonOutput) {

    public LLMRequest {
        systemText = systemText == null ? "" : systemText;
        messages = List.copyOf(messages);
        tools = tools == null ? List.of() : List.copyOf(tools);
        if (messages.isEmpty()) {
            throw new IllegalArgumentException("A request needs at least one message");
        }
    }

    /** A copy with extra messages at the end (used for the agent loop and for repair requests). */
    public LLMRequest withAppended(LLMMessage... extra) {
        List<LLMMessage> all = new ArrayList<>(messages);
        all.addAll(List.of(extra));
        return new LLMRequest(systemText, all, tools, jsonOutput);
    }

    /** Text of the last user message, mainly for logging and tests. */
    public String lastUserText() {
        for (int i = messages.size() - 1; i >= 0; i--) {
            if (messages.get(i).role() == LLMMessage.Role.USER) {
                return messages.get(i).text();
            }
        }
        return "";
    }
}
