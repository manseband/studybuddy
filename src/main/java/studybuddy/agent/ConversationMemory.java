package studybuddy.agent;

import java.util.ArrayList;
import java.util.List;

/** What was said in the current session (F09). Only the most recent turns are kept. */
public class ConversationMemory {

    /** One message of the conversation. */
    public record ChatTurn(boolean fromStudent, String text) {
    }

    private final List<ChatTurn> turns = new ArrayList<>();
    private final int maxTurns;

    public ConversationMemory(int maxTurns) {
        this.maxTurns = maxTurns;
    }

    public void addTurn(boolean fromStudent, String text) {
        turns.add(new ChatTurn(fromStudent, text));
        if (turns.size() > maxTurns) {
            turns.removeFirst();
        }
    }

    public List<ChatTurn> getTurns() {
        return List.copyOf(turns);
    }

    public void clear() {
        turns.clear();
    }
}
