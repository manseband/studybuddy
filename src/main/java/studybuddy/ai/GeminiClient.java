package studybuddy.ai;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.exception.NonRetriableException;
import dev.langchain4j.exception.RetriableException;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.request.ResponseFormat;
import dev.langchain4j.model.chat.request.json.JsonArraySchema;
import dev.langchain4j.model.chat.request.json.JsonBooleanSchema;
import dev.langchain4j.model.chat.request.json.JsonEnumSchema;
import dev.langchain4j.model.chat.request.json.JsonIntegerSchema;
import dev.langchain4j.model.chat.request.json.JsonNumberSchema;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;
import dev.langchain4j.model.chat.request.json.JsonSchemaElement;
import dev.langchain4j.model.chat.request.json.JsonStringSchema;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.googleai.GoogleAiGeminiChatModel;
import dev.langchain4j.model.output.TokenUsage;

/**
 * Adapter from our {@link LLMClient} interface to LangChain4j's {@link ChatModel} for Google Gemini.
 * It converts {@link LLMRequest}/{@link LLMResponse} to and from LangChain4j's ChatRequest/ChatResponse,
 * so no other package depends on LangChain4j.
 *
 * Retry policy (F02): timeouts, rate limits and server errors are retried up to maxRetries times with
 * exponential backoff; other errors (e.g. an invalid API key) fail immediately.
 */
public class GeminiClient implements LLMClient {

    private final ChatModel chatModel;
    private final String modelName;
    private final int maxRetries;
    private final long backoffMillis;
    private final ResponseParser parser = new ResponseParser();

    /**
     * @param chatModel     the LangChain4j model to adapt
     * @param modelName     model ID, kept for error messages and logs
     * @param maxRetries    extra attempts after a retryable failure
     * @param backoffMillis wait before the first retry; doubles on each further retry
     */
    public GeminiClient(ChatModel chatModel, String modelName, int maxRetries, long backoffMillis) {
        this.chatModel = chatModel;
        this.modelName = modelName;
        this.maxRetries = maxRetries;
        this.backoffMillis = backoffMillis;
    }

    /** Creates a client for the Gemini API. LangChain4j's own retries are off; this class retries. */
    public static GeminiClient create(String apiKey, String modelName, Duration timeout, int maxRetries) {
        ChatModel model = GoogleAiGeminiChatModel.builder()
                .apiKey(apiKey)
                .modelName(modelName)
                .timeout(timeout)
                .maxRetries(0)
                .build();
        return new GeminiClient(model, modelName, maxRetries, 1000);
    }

    @Override
    public LLMResponse chat(LLMRequest request) {
        ChatRequest chatRequest = toChatRequest(request);
        LLMException last = null;
        for (int attempt = 0; attempt <= maxRetries; attempt++) {
            if (attempt > 0) {
                pause(backoffMillis << (attempt - 1));
            }
            try {
                LLMResponse response = toLLMResponse(chatModel.chat(chatRequest));
                if (response.text().isBlank() && !response.hasToolCalls()) {
                    throw new LLMException(modelName + " returned an empty reply", true);
                }
                return response;
            } catch (LLMException e) {
                last = e;
            } catch (RuntimeException e) {
                last = translate(e);
            }
            if (!last.isRetryable()) {
                throw last;
            }
        }
        throw new LLMException("The AI service did not respond after " + (maxRetries + 1) + " attempts: "
                + last.getMessage(), false, last);
    }

    ChatRequest toChatRequest(LLMRequest request) {
        List<ChatMessage> messages = new ArrayList<>();
        if (!request.systemText().isBlank()) {
            messages.add(SystemMessage.from(request.systemText()));
        }
        for (LLMMessage m : request.messages()) {
            messages.add(switch (m.role()) {
                case USER -> UserMessage.from(m.text());
                case ASSISTANT -> m.toolCalls().isEmpty()
                        ? AiMessage.from(m.text())
                        : AiMessage.from(m.text().isBlank() ? null : m.text(), toExecutionRequests(m.toolCalls()));
                case TOOL -> ToolExecutionResultMessage.from(m.toolCallId(), m.toolName(), m.text());
            });
        }
        ChatRequest.Builder builder = ChatRequest.builder().messages(messages);
        if (!request.tools().isEmpty()) {
            builder.toolSpecifications(request.tools().stream().map(this::toToolSpecification).toList());
        } else if (request.jsonOutput()) {
            // JSON mode cannot be combined with tools in Gemini, so it is only used without tools
            builder.responseFormat(ResponseFormat.JSON);
        }
        return builder.build();
    }

    LLMResponse toLLMResponse(ChatResponse response) {
        AiMessage ai = response.aiMessage();
        List<ToolCall> calls = new ArrayList<>();
        if (ai.hasToolExecutionRequests()) {
            int n = 0;
            for (ToolExecutionRequest r : ai.toolExecutionRequests()) {
                n++;
                String id = r.id() == null || r.id().isBlank() ? "call-" + n : r.id();
                calls.add(new ToolCall(id, r.name(), parser.parseMap(r.arguments())));
            }
        }
        TokenUsage usage = response.tokenUsage();
        int in = usage == null || usage.inputTokenCount() == null ? 0 : usage.inputTokenCount();
        int out = usage == null || usage.outputTokenCount() == null ? 0 : usage.outputTokenCount();
        return new LLMResponse(ai.text(), calls, in, out);
    }

    private List<ToolExecutionRequest> toExecutionRequests(List<ToolCall> calls) {
        return calls.stream()
                .map(c -> ToolExecutionRequest.builder().id(c.id()).name(c.name())
                        .arguments(parser.toJson(c.arguments())).build())
                .toList();
    }

    private ToolSpecification toToolSpecification(ToolSpec spec) {
        return ToolSpecification.builder()
                .name(spec.name())
                .description(spec.description())
                .parameters(toObjectSchema(spec.parameterSchema()))
                .build();
    }

    /**
     * Converts the JSON Schema text used by our tools into LangChain4j's schema objects.
     * Supports the subset our tools use: object, string, integer, number, boolean, array and enum.
     */
    static JsonObjectSchema toObjectSchema(String schemaJson) {
        try {
            JsonNode root = new ObjectMapper().readTree(schemaJson == null || schemaJson.isBlank() ? "{}" : schemaJson);
            // a schema without "type" is treated as an object by toElement
            return (JsonObjectSchema) toElement(root);
        } catch (Exception e) {
            throw new IllegalArgumentException("Invalid tool parameter schema: " + e.getMessage(), e);
        }
    }

    private static JsonSchemaElement toElement(JsonNode node) {
        String description = node.path("description").asText(null);
        if (node.has("enum")) {
            List<String> values = new ArrayList<>();
            node.get("enum").forEach(v -> values.add(v.asText()));
            return JsonEnumSchema.builder().enumValues(values).description(description).build();
        }
        return switch (node.path("type").asText("object")) {
            case "string" -> JsonStringSchema.builder().description(description).build();
            case "integer" -> JsonIntegerSchema.builder().description(description).build();
            case "number" -> JsonNumberSchema.builder().description(description).build();
            case "boolean" -> JsonBooleanSchema.builder().description(description).build();
            case "array" -> JsonArraySchema.builder().description(description)
                    .items(toElement(node.path("items").isMissingNode()
                            ? new ObjectMapper().createObjectNode().put("type", "string")
                            : node.get("items")))
                    .build();
            default -> {
                JsonObjectSchema.Builder object = JsonObjectSchema.builder().description(description);
                for (Map.Entry<String, JsonNode> property : node.path("properties").properties()) {
                    object.addProperty(property.getKey(), toElement(property.getValue()));
                }
                List<String> required = new ArrayList<>();
                node.path("required").forEach(r -> required.add(r.asText()));
                if (!required.isEmpty()) {
                    object.required(required);
                }
                yield object.build();
            }
        };
    }

    private LLMException translate(RuntimeException e) {
        if (e instanceof RetriableException || e.getCause() instanceof java.io.IOException) {
            return new LLMException("Temporary problem reaching " + modelName + ": " + e.getMessage(), true, e);
        }
        if (e instanceof NonRetriableException) {
            return new LLMException(modelName + " rejected the request: " + e.getMessage(), false, e);
        }
        return new LLMException("Unexpected error from " + modelName + ": " + e.getMessage(), false, e);
    }

    private static void pause(long millis) {
        if (millis <= 0) {
            return;
        }
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new LLMException("Interrupted while waiting to retry", false, e);
        }
    }
}
