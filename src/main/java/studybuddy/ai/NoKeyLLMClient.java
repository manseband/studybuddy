package studybuddy.ai;

/**
 * LLMClient used when no Gemini API key is configured. Every call fails with a message that tells the
 * student how to add a key, so AI features explain themselves instead of crashing, while
 * deterministic features keep working.
 */
public class NoKeyLLMClient implements LLMClient {

    public static final String MESSAGE = "This feature needs a Gemini API key. Copy .env.example to .env, "
            + "set GEMINI_API_KEY to your key, and run StudyBuddy again.";

    @Override
    public LLMResponse chat(LLMRequest request) {
        throw new LLMException(MESSAGE, false);
    }
}
