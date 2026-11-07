package studybuddy.agent;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import studybuddy.application.FlashcardService;
import studybuddy.domain.CreatedItem;
import studybuddy.domain.Flashcard;

/** Creates and saves a flashcard deck with the normal generation and validation. */
public class GenerateFlashcardsTool implements AgentTool {

    private static final int DEFAULT_COUNT = 10;

    private final FlashcardService flashcardService;

    public GenerateFlashcardsTool(FlashcardService flashcardService) {
        this.flashcardService = flashcardService;
    }

    @Override
    public String getName() {
        return "generate_flashcards";
    }

    @Override
    public String getDescription() {
        return "Creates and saves a new flashcard deck from a course's materials or concepts. Leave out conceptIds "
                + "and materialIds to cover the whole course. The student reviews it in the Flashcards tab.";
    }

    @Override
    public String getParameterSchema() {
        return """
                {"type": "object",
                 "properties": {
                   "courseId": {"type": "integer", "description": "Course id"},
                   "count": {"type": "integer", "minimum": %d, "maximum": %d, "description": "Number of cards; default %d"},
                   "conceptIds": {"type": "array", "items": {"type": "integer"}, "description": "Only these concepts"},
                   "materialIds": {"type": "array", "items": {"type": "integer"}, "description": "Only these materials"}},
                 "required": ["courseId"]}""".formatted(Flashcard.MIN_DECK_SIZE, Flashcard.MAX_DECK_SIZE, DEFAULT_COUNT);
    }

    @Override
    public String describe(Map<String, Object> args) {
        return "Generating " + AgentTool.intArg(args, "count", DEFAULT_COUNT) + "-card flashcard deck";
    }

    @Override
    public ToolResult execute(Map<String, Object> args) {
        int count = AgentTool.intArg(args, "count", DEFAULT_COUNT);
        List<Flashcard> cards = flashcardService.saveDeck(flashcardService.generateCards(
                AgentTool.longArg(args, "courseId"), AgentTool.idsArg(args, "materialIds"),
                AgentTool.idsArg(args, "conceptIds"), count));
        long deckId = cards.get(0).getDeckId();

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("deckId", deckId);
        data.put("requested", count);
        data.put("created", cards.size());
        data.put("fronts", cards.stream().map(Flashcard::getFront).toList());
        String label = "Flashcard deck #" + deckId + " (" + cards.size() + " cards)";
        return ToolResult.ok("Created " + label, data, List.of(new CreatedItem(CreatedItem.Type.DECK, deckId, label)));
    }
}
