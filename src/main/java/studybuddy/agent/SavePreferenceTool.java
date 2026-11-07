package studybuddy.agent;

import java.util.Map;

import studybuddy.persistence.PreferenceRepository;

/** Adds to the student's preference profile, which is kept across sessions. It never changes earlier entries. */
public class SavePreferenceTool implements AgentTool {

    static final int MAX_LENGTH = 200;

    private final PreferenceRepository preferences;

    public SavePreferenceTool(PreferenceRepository preferences) {
        this.preferences = preferences;
    }

    @Override
    public String getName() {
        return "save_preference";
    }

    @Override
    public String getDescription() {
        return "Remembers a lasting preference the student stated, such as \"prefers short answers\", for future "
                + "conversations. Do not use it for one-off requests.";
    }

    @Override
    public String getParameterSchema() {
        return """
                {"type": "object",
                 "properties": {"preference": {"type": "string", "description": "The preference, in a few words"}},
                 "required": ["preference"]}""";
    }

    @Override
    public String describe(Map<String, Object> args) {
        return "Remembering your preference";
    }

    @Override
    public ToolResult execute(Map<String, Object> args) {
        String preference = AgentTool.stringArg(args, "preference", "").strip();
        if (preference.isEmpty() || preference.length() > MAX_LENGTH) {
            throw new IllegalArgumentException("A preference must have between 1 and " + MAX_LENGTH + " characters.");
        }
        preferences.add(preference);
        return ToolResult.ok("Saved preference: " + preference, Map.of("saved", preference));
    }
}
