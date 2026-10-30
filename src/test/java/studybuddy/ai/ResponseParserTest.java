package studybuddy.ai;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import studybuddy.domain.Answer;
import studybuddy.domain.Chunk;
import studybuddy.domain.Citation;

class ResponseParserTest {

    record AnswerJson(String answer, List<Integer> citations) {
    }

    private final ResponseParser parser = new ResponseParser();

    @Test
    void parsesPlainFencedAndChattyJson() {
        assertEquals("A", parser.parse("{\"answer\":\"A\",\"citations\":[1]}", AnswerJson.class).answer());
        assertEquals(List.of(1, 2),
                parser.parse("```json\n{\"answer\":\"B\",\"citations\":[1,2]}\n```", AnswerJson.class).citations());
        assertEquals("C", parser.parse("Here you go:\n{\"answer\":\"C\",\"extra\":true}\nThanks!", AnswerJson.class)
                .answer());
    }

    @Test
    void malformedJsonIsReported() {
        assertThrows(ResponseFormatException.class, () -> parser.parse("no json here", AnswerJson.class));
        assertThrows(ResponseFormatException.class, () -> parser.parse("{\"answer\": ", AnswerJson.class));
        assertThrows(ResponseFormatException.class, () -> parser.parse("{\"answer\": [1,}", AnswerJson.class));
    }

    @Test
    void repairRequestIsSentOnceForMalformedJson() {
        FakeLLMClient fake = new FakeLLMClient()
                .enqueueText("{\"answer\": oops")
                .enqueueText("{\"answer\":\"fixed\",\"citations\":[]}");
        LLMRequest request = new PromptBuilder().withOutputSchema("{}").withUserMessage("q").build();

        AnswerJson result = parser.requestJson(fake, request, AnswerJson.class);

        assertEquals("fixed", result.answer());
        LLMRequest repair = fake.lastRequest();
        assertEquals(3, repair.messages().size());
        assertEquals("{\"answer\": oops", repair.messages().get(1).text());
        assertEquals(ResponseParser.REPAIR_INSTRUCTION, repair.lastUserText());
    }

    @Test
    void secondMalformedReplyFails() {
        FakeLLMClient fake = new FakeLLMClient().enqueueText("bad").enqueueText("still bad");
        LLMRequest request = new PromptBuilder().withUserMessage("q").build();

        assertThrows(ResponseFormatException.class, () -> parser.requestJson(fake, request, AnswerJson.class));
        assertEquals(0, fake.remaining());
    }

    @Test
    void citationsToMissingExcerptsAreRemoved() {
        List<Chunk> excerpts = List.of(new Chunk(10, 5, 1, 0, "Facade text", 3), new Chunk(11, 5, 1, 1, "Adapter text", 7));
        Answer answer = new Answer("Adapter converts [2]; Facade simplifies [1] [4].",
                List.of(new Citation(2, 0, 0, "notes.pdf", 0, ""), new Citation(1, 0, 0, "notes.pdf", 0, ""),
                        new Citation(4, 0, 0, "notes.pdf", 0, ""), new Citation(2, 0, 0, "notes.pdf", 0, "")),
                true, false);

        Answer checked = parser.validateCitations(answer, excerpts);

        assertTrue(checked.partiallyUnverified());
        assertEquals(2, checked.citations().size());
        Citation first = checked.citations().get(0);
        assertEquals(11, first.chunkId());
        assertEquals(7, first.page());
        assertEquals("Adapter text", first.excerpt());
    }

    @Test
    void validCitationsKeepAnswerVerified() {
        Answer answer = new Answer("x [1]", List.of(new Citation(1, 0, 0, "t", 0, "")), true, false);
        assertFalse(parser.validateCitations(answer, List.of(new Chunk(1, 1, 1, 0, "text", 1))).partiallyUnverified());
    }

    @Test
    void parsesToolArgumentsAndWritesJson() {
        Map<String, Object> args = parser.parseMap("{\"courseId\": 3, \"query\": \"observer\"}");
        assertEquals(3, args.get("courseId"));
        assertEquals(Map.of(), parser.parseMap(""));
        assertEquals("{\"answer\":\"A\",\"citations\":[1]}", parser.toJson(new AnswerJson("A", List.of(1))));
    }
}
