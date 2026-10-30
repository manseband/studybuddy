package studybuddy.ai;

/**
 * Strategy interface for talking to a chat model. Services and the agent depend only on this,
 * so the provider can be swapped (GeminiClient in the app, FakeLLMClient in tests).
 */
public interface LLMClient {

    /**
     * Sends the request and returns the model's reply.
     *
     * @throws LLMException if the model cannot be reached or fails after retries
     */
    LLMResponse chat(LLMRequest request);
}
