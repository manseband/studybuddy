package studybuddy.ai;

import java.util.ArrayList;
import java.util.List;

import studybuddy.domain.Chunk;

/**
 * Builder for {@link LLMRequest}s. Every AI feature needs a slightly different mix of parts
 * (system role, numbered excerpts, rules, a JSON output format, tools, conversation history),
 * and the builder assembles them in one consistent layout:
 *
 * <pre>
 * system:  role text + rules + "respond with JSON in this format"
 * history: earlier turns (agent only)
 * user:    numbered excerpts + the user's message
 * </pre>
 *
 * Excerpts are numbered from 1 so the model can cite them as [1], [2], ...
 */
public class PromptBuilder {

    private String systemText = "";
    private final List<Chunk> excerpts = new ArrayList<>();
    private final List<String> instructions = new ArrayList<>();
    private String outputSchema;
    private final List<ToolSpec> toolSpecs = new ArrayList<>();
    private final List<LLMMessage> history = new ArrayList<>();
    private String userMessage;

    public PromptBuilder system(String text) {
        this.systemText = text == null ? "" : text.strip();
        return this;
    }

    public PromptBuilder withExcerpts(List<Chunk> chunks) {
        excerpts.addAll(chunks);
        return this;
    }

    public PromptBuilder withInstruction(String text) {
        if (text != null && !text.isBlank()) {
            instructions.add(text.strip());
        }
        return this;
    }

    /** Asks for a single JSON object in the given format (a JSON example or schema). */
    public PromptBuilder withOutputSchema(String schema) {
        this.outputSchema = schema;
        return this;
    }

    public PromptBuilder withTools(List<ToolSpec> specs) {
        toolSpecs.addAll(specs);
        return this;
    }

    /** Earlier conversation turns, placed before the new user message. */
    public PromptBuilder withHistory(List<LLMMessage> turns) {
        history.addAll(turns);
        return this;
    }

    public PromptBuilder withUserMessage(String text) {
        this.userMessage = text;
        return this;
    }

    public LLMRequest build() {
        List<LLMMessage> messages = new ArrayList<>(history);
        String userText = buildUserText();
        if (!userText.isEmpty()) {
            messages.add(LLMMessage.user(userText));
        }
        if (messages.isEmpty()) {
            throw new IllegalStateException("A prompt needs a user message, excerpts or history");
        }
        return new LLMRequest(buildSystemText(), messages, toolSpecs, outputSchema != null);
    }

    private String buildSystemText() {
        StringBuilder sb = new StringBuilder(systemText);
        if (!instructions.isEmpty()) {
            appendSection(sb, "Rules:");
            for (String rule : instructions) {
                sb.append("\n- ").append(rule);
            }
        }
        if (outputSchema != null) {
            appendSection(sb, "Respond with a single JSON object and nothing else, in this format:\n");
            sb.append(outputSchema.strip());
        }
        return sb.toString();
    }

    private String buildUserText() {
        StringBuilder sb = new StringBuilder();
        if (!excerpts.isEmpty()) {
            sb.append(formatExcerpts(excerpts));
        }
        if (userMessage != null && !userMessage.isBlank()) {
            appendSection(sb, userMessage.strip());
        }
        return sb.toString();
    }

    /** "[1] (page 3)\ntext" blocks separated by blank lines. */
    public static String formatExcerpts(List<Chunk> chunks) {
        StringBuilder sb = new StringBuilder("Excerpts from the course materials:");
        for (int i = 0; i < chunks.size(); i++) {
            Chunk chunk = chunks.get(i);
            sb.append("\n\n[").append(i + 1).append("] (page ").append(chunk.getPageNumber()).append(")\n")
              .append(chunk.getText().strip());
        }
        return sb.toString();
    }

    private static void appendSection(StringBuilder sb, String text) {
        if (!sb.isEmpty()) {
            sb.append("\n\n");
        }
        sb.append(text);
    }
}
