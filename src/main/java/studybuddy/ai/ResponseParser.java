package studybuddy.ai;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import studybuddy.domain.Answer;
import studybuddy.domain.Chunk;
import studybuddy.domain.Citation;

/**
 * Turns model replies into Java objects and checks them deterministically.
 * Models sometimes wrap JSON in ```json fences or add a sentence before it, so the JSON part is
 * extracted first. Malformed JSON gets exactly one repair request (F02 error policy).
 */
public class ResponseParser {

    private static final int EXCERPT_PREVIEW_CHARS = 300;
    static final String REPAIR_INSTRUCTION =
            "Your previous reply was not valid JSON in the required format. "
            + "Reply again with only the corrected JSON object, no other text.";

    private final ObjectMapper mapper = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
            .configure(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS, false);

    /**
     * Parses the JSON contained in a model reply.
     *
     * @throws ResponseFormatException if no valid JSON of the expected shape is found
     */
    public <T> T parse(String json, Class<T> type) {
        String extracted = extractJson(json);
        try {
            T value = mapper.readValue(extracted, type);
            if (value == null) {
                throw new ResponseFormatException("The reply contained null instead of a JSON object");
            }
            return value;
        } catch (JsonProcessingException e) {
            throw new ResponseFormatException("Reply is not valid JSON for " + type.getSimpleName() + ": "
                    + e.getOriginalMessage(), e);
        }
    }

    /** Parses a JSON object into a map (used for tool-call arguments). */
    public Map<String, Object> parseMap(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            return mapper.readValue(extractJson(json), new TypeReference<Map<String, Object>>() { });
        } catch (JsonProcessingException e) {
            throw new ResponseFormatException("Arguments are not a JSON object: " + e.getOriginalMessage(), e);
        }
    }

    public String toJson(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Cannot convert to JSON: " + e.getOriginalMessage(), e);
        }
    }

    public ObjectMapper mapper() {
        return mapper;
    }

    /**
     * Sends the request and parses the reply. If the reply is malformed, sends one repair request
     * that shows the model its own reply and asks for corrected JSON.
     *
     * @throws ResponseFormatException if the repaired reply is still malformed
     * @throws LLMException            if the model cannot be reached
     */
    public <T> T requestJson(LLMClient llm, LLMRequest request, Class<T> type) {
        LLMResponse first = llm.chat(request);
        try {
            return parse(first.text(), type);
        } catch (ResponseFormatException firstError) {
            LLMRequest repair = request.withAppended(LLMMessage.assistant(first.text()),
                    LLMMessage.user(REPAIR_INSTRUCTION));
            return parse(llm.chat(repair).text(), type);
        }
    }

    /**
     * Keeps only citations that refer to an excerpt that was actually provided (numbered from 1) and
     * fills in their chunk, material, page and excerpt text from that chunk. Citations to excerpts that
     * were not provided are removed and the answer is marked partially unverified (F04 step 6).
     */
    public Answer validateCitations(Answer answer, List<Chunk> excerpts) {
        List<Citation> valid = new ArrayList<>();
        Set<Integer> seen = new HashSet<>();
        boolean removed = false;
        for (Citation citation : answer.citations()) {
            int n = citation.excerptNumber();
            if (n < 1 || n > excerpts.size()) {
                removed = true;
                continue;
            }
            if (!seen.add(n)) {
                continue;
            }
            Chunk chunk = excerpts.get(n - 1);
            valid.add(new Citation(n, chunk.getId(), chunk.getMaterialId(), citation.materialTitle(),
                    chunk.getPageNumber(), preview(chunk.getText())));
        }
        return answer.withCitations(valid, removed);
    }

    /** Removes ```json fences and any text around the outermost JSON object or array. */
    static String extractJson(String text) {
        if (text == null) {
            throw new ResponseFormatException("The reply was empty");
        }
        String s = text.strip();
        if (s.startsWith("```")) {
            int firstNewline = s.indexOf('\n');
            s = firstNewline < 0 ? "" : s.substring(firstNewline + 1);
            int fence = s.lastIndexOf("```");
            if (fence >= 0) {
                s = s.substring(0, fence);
            }
            s = s.strip();
        }
        int start = firstIndexOfAny(s, '{', '[');
        if (start < 0) {
            throw new ResponseFormatException("The reply did not contain a JSON object");
        }
        char close = s.charAt(start) == '{' ? '}' : ']';
        int end = s.lastIndexOf(close);
        if (end < start) {
            throw new ResponseFormatException("The JSON in the reply is incomplete");
        }
        return s.substring(start, end + 1);
    }

    private static int firstIndexOfAny(String s, char a, char b) {
        int ia = s.indexOf(a);
        int ib = s.indexOf(b);
        if (ia < 0) {
            return ib;
        }
        if (ib < 0) {
            return ia;
        }
        return Math.min(ia, ib);
    }

    private static String preview(String text) {
        String t = text.strip();
        return t.length() <= EXCERPT_PREVIEW_CHARS ? t : t.substring(0, EXCERPT_PREVIEW_CHARS) + "...";
    }
}
