package studybuddy.ai;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import org.junit.jupiter.api.Test;

import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.exception.AuthenticationException;
import dev.langchain4j.exception.TimeoutException;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.request.ResponseFormat;
import dev.langchain4j.model.chat.request.json.JsonIntegerSchema;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.output.TokenUsage;

/** Tests the adapter against a scripted LangChain4j ChatModel; no network calls. */
class GeminiClientTest {

    /** ChatModel stand-in that records requests and replays scripted results. */
    static class ScriptedChatModel implements ChatModel {
        final Deque<Supplier<ChatResponse>> script = new ArrayDeque<>();
        final List<ChatRequest> received = new ArrayList<>();

        @Override
        public ChatResponse doChat(ChatRequest request) {
            received.add(request);
            return script.removeFirst().get();
        }

        ScriptedChatModel reply(AiMessage message) {
            script.add(() -> ChatResponse.builder().aiMessage(message).tokenUsage(new TokenUsage(12, 5)).build());
            return this;
        }

        ScriptedChatModel fail(RuntimeException e) {
            script.add(() -> {
                throw e;
            });
            return this;
        }
    }

    private static LLMRequest simpleRequest(boolean json) {
        PromptBuilder builder = new PromptBuilder().system("You are helpful.").withUserMessage("Hi");
        if (json) {
            builder.withOutputSchema("{\"a\":1}");
        }
        return builder.build();
    }

    @Test
    void convertsRequestAndResponse() {
        ScriptedChatModel model = new ScriptedChatModel().reply(AiMessage.from("{\"a\":1}"));
        GeminiClient client = new GeminiClient(model, "test-model", 2, 0);

        LLMResponse response = client.chat(simpleRequest(true));

        assertEquals("{\"a\":1}", response.text());
        assertEquals(12, response.inputTokens());
        assertEquals(5, response.outputTokens());
        ChatRequest sent = model.received.get(0);
        assertInstanceOf(SystemMessage.class, sent.messages().get(0));
        assertEquals(2, sent.messages().size());
        assertEquals(ResponseFormat.JSON, sent.responseFormat());
    }

    @Test
    void retriesTemporaryFailuresThenSucceeds() {
        ScriptedChatModel model = new ScriptedChatModel()
                .fail(new TimeoutException("slow"))
                .fail(new TimeoutException("slow again"))
                .reply(AiMessage.from("ok"));

        assertEquals("ok", new GeminiClient(model, "m", 2, 0).chat(simpleRequest(false)).text());
        assertEquals(3, model.received.size());
    }

    @Test
    void givesUpAfterMaxRetries() {
        ScriptedChatModel model = new ScriptedChatModel()
                .fail(new TimeoutException("1")).fail(new TimeoutException("2")).fail(new TimeoutException("3"));

        LLMException e = assertThrows(LLMException.class, () -> new GeminiClient(model, "m", 2, 0).chat(simpleRequest(false)));
        assertTrue(e.getMessage().contains("3 attempts"));
    }

    @Test
    void doesNotRetryAuthenticationErrors() {
        ScriptedChatModel model = new ScriptedChatModel().fail(new AuthenticationException("bad key"));

        LLMException e = assertThrows(LLMException.class, () -> new GeminiClient(model, "m", 2, 0).chat(simpleRequest(false)));
        assertFalse(e.isRetryable());
        assertEquals(1, model.received.size());
    }

    @Test
    void mapsToolCallsBothWays() {
        ToolExecutionRequest call = ToolExecutionRequest.builder()
                .id("abc").name("get_weak_topics").arguments("{\"courseId\": 4}").build();
        ScriptedChatModel model = new ScriptedChatModel().reply(AiMessage.from(List.of(call)));
        ToolSpec spec = new ToolSpec("get_weak_topics", "Weak topics of a course",
                "{\"type\":\"object\",\"properties\":{\"courseId\":{\"type\":\"integer\",\"description\":\"Course\"}},"
                        + "\"required\":[\"courseId\"]}");
        LLMRequest request = new PromptBuilder().withTools(List.of(spec)).withOutputSchema("{}")
                .withUserMessage("What am I weak at?").build();
        GeminiClient client = new GeminiClient(model, "m", 0, 0);

        LLMResponse response = client.chat(request);

        assertTrue(response.hasToolCalls());
        ToolCall parsed = response.toolCalls().get(0);
        assertEquals("get_weak_topics", parsed.name());
        assertEquals(Map.of("courseId", 4), parsed.arguments());

        ChatRequest sent = model.received.get(0);
        assertEquals(1, sent.toolSpecifications().size());
        JsonObjectSchema params = sent.toolSpecifications().get(0).parameters();
        assertInstanceOf(JsonIntegerSchema.class, params.properties().get("courseId"));
        assertEquals(List.of("courseId"), params.required());
        assertFalse(ResponseFormat.JSON.equals(sent.responseFormat()), "JSON mode is not combined with tools");

        // follow-up request carries the tool call and its result back to the model
        ChatRequest followUp = client.toChatRequest(request.withAppended(
                LLMMessage.assistantToolCalls("", List.of(parsed)), LLMMessage.toolResult(parsed, "[]")));
        // messages: system, user, assistant tool call, tool result
        assertTrue(((AiMessage) followUp.messages().get(2)).hasToolExecutionRequests());
        ToolExecutionResultMessage result = (ToolExecutionResultMessage) followUp.messages().get(3);
        assertEquals("abc", result.id());
        assertEquals("[]", result.text());
    }

    @Test
    void emptySchemaBecomesEmptyObject() {
        assertTrue(GeminiClient.toObjectSchema("").properties().isEmpty());
        assertThrows(IllegalArgumentException.class, () -> GeminiClient.toObjectSchema("{not json"));
    }
}
