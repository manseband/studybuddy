package studybuddy.ai;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import studybuddy.domain.Chunk;

class PromptBuilderAndFakeClientTest {

    @Test
    void builderLaysOutSystemExcerptsAndQuestion() {
        LLMRequest request = new PromptBuilder()
                .system("You are a study assistant.")
                .withInstruction("Answer only from the excerpts.")
                .withInstruction("Cite excerpt numbers like [1].")
                .withOutputSchema("{\"answer\": \"...\", \"citations\": [1]}")
                .withExcerpts(List.of(new Chunk(1, 1, 1, 0, "Facade hides a subsystem.", 3),
                        new Chunk(2, 1, 1, 1, "Adapter converts an interface.", 4)))
                .withUserMessage("How is Adapter different from Facade?")
                .build();

        assertTrue(request.jsonOutput());
        assertTrue(request.systemText().startsWith("You are a study assistant."));
        assertTrue(request.systemText().contains("- Cite excerpt numbers like [1]."));
        assertTrue(request.systemText().contains("{\"answer\""));

        assertEquals(1, request.messages().size());
        String user = request.lastUserText();
        assertTrue(user.contains("[1] (page 3)\nFacade hides a subsystem."));
        assertTrue(user.contains("[2] (page 4)\nAdapter converts an interface."));
        assertTrue(user.endsWith("How is Adapter different from Facade?"));
    }

    @Test
    void multiPageExcerptsShowTheirPageRange() {
        String text = PromptBuilder.formatExcerpts(List.of(new Chunk(1, 1, 1, 0, "Slides on Observer.", 4, 6)));
        assertTrue(text.contains("[1] (pages 4-6)\nSlides on Observer."));
    }

    @Test
    void historyComesBeforeTheNewMessageAndToolsArePassedOn() {
        ToolSpec spec = new ToolSpec("get_weak_topics", "Lists weak topics", "{\"type\":\"object\"}");
        LLMRequest request = new PromptBuilder()
                .system("Coach")
                .withHistory(List.of(LLMMessage.user("hi"), LLMMessage.assistant("hello")))
                .withTools(List.of(spec))
                .withUserMessage("quiz me")
                .build();

        assertEquals(3, request.messages().size());
        assertEquals("quiz me", request.messages().get(2).text());
        assertEquals(List.of(spec), request.tools());
        assertFalse(request.jsonOutput());
    }

    @Test
    void emptyPromptIsRejected() {
        assertThrows(IllegalStateException.class, () -> new PromptBuilder().system("only system").build());
    }

    @Test
    void fakeClientReturnsScriptedRepliesInOrderAndRecordsRequests() {
        LLMException failure = new LLMException("timeout", true);
        FakeLLMClient fake = new FakeLLMClient()
                .enqueueText("first")
                .enqueueFailure(failure)
                .enqueue(LLMResponse.toolCalls(new ToolCall("c1", "search", Map.of("query", "observer"))));
        LLMRequest request = new PromptBuilder().withUserMessage("q").build();

        assertEquals("first", fake.chat(request).text());
        assertSame(failure, assertThrows(LLMException.class, () -> fake.chat(request)));
        LLMResponse third = fake.chat(request);
        assertTrue(third.hasToolCalls());
        assertEquals("observer", third.toolCalls().get(0).arguments().get("query"));
        assertEquals(3, fake.getRequests().size());
        assertThrows(IllegalStateException.class, () -> fake.chat(request));
    }

    @Test
    void appendingMessagesKeepsTheRestOfTheRequest() {
        LLMRequest request = new PromptBuilder().system("s").withOutputSchema("{}").withUserMessage("q").build();
        ToolCall call = new ToolCall("c1", "search", Map.of());

        LLMRequest next = request.withAppended(LLMMessage.assistantToolCalls("", List.of(call)),
                LLMMessage.toolResult(call, "3 results"));

        assertEquals(3, next.messages().size());
        assertEquals("c1", next.messages().get(2).toolCallId());
        assertTrue(next.jsonOutput());
        assertEquals("q", next.lastUserText());
    }
}
