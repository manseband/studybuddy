package studybuddy.ai;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.function.Supplier;

/**
 * LLMClient for tests: returns scripted responses in order and records every request it receives,
 * so tests can check prompts without calling the real API.
 */
public class FakeLLMClient implements LLMClient {

    private final Queue<Supplier<LLMResponse>> scripted = new ArrayDeque<>();
    private final List<LLMRequest> requests = new ArrayList<>();

    @Override
    public synchronized LLMResponse chat(LLMRequest request) {
        requests.add(request);
        Supplier<LLMResponse> next = scripted.poll();
        if (next == null) {
            throw new IllegalStateException("FakeLLMClient has no scripted response left for: " + request.lastUserText());
        }
        return next.get();
    }

    public synchronized FakeLLMClient enqueue(LLMResponse response) {
        scripted.add(() -> response);
        return this;
    }

    /** Shortcut for a plain text (or JSON text) reply. */
    public FakeLLMClient enqueueText(String text) {
        return enqueue(LLMResponse.text(text));
    }

    /** The next call throws the given exception, e.g. to test retry and fallback paths. */
    public synchronized FakeLLMClient enqueueFailure(RuntimeException error) {
        scripted.add(() -> {
            throw error;
        });
        return this;
    }

    public synchronized List<LLMRequest> getRequests() {
        return List.copyOf(requests);
    }

    public synchronized LLMRequest lastRequest() {
        if (requests.isEmpty()) {
            throw new IllegalStateException("No request was sent");
        }
        return requests.get(requests.size() - 1);
    }

    public synchronized int remaining() {
        return scripted.size();
    }
}
