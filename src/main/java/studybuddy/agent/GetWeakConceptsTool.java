package studybuddy.agent;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import studybuddy.application.MasteryService;
import studybuddy.domain.ConceptMastery;

/** Reports how well the student knows each concept, using the normal mastery calculation. */
public class GetWeakConceptsTool implements AgentTool {

    private final MasteryService masteryService;

    public GetWeakConceptsTool(MasteryService masteryService) {
        this.masteryService = masteryService;
    }

    @Override
    public String getName() {
        return "get_weak_concepts";
    }

    @Override
    public String getDescription() {
        return "Returns the student's mastery of every concept in a course, weakest first, from their quiz answers "
                + "and flashcard ratings. Use it to find what the student keeps getting wrong.";
    }

    @Override
    public String getParameterSchema() {
        return """
                {"type": "object",
                 "properties": {"courseId": {"type": "integer", "description": "Course id"}},
                 "required": ["courseId"]}""";
    }

    @Override
    public String describe(Map<String, Object> args) {
        return "Checking weak concepts";
    }

    @Override
    public ToolResult execute(Map<String, Object> args) {
        List<ConceptMastery> mastery = masteryService.computeMastery(AgentTool.longArg(args, "courseId"));
        long weak = mastery.stream().filter(ConceptMastery::weak).count();
        List<Map<String, Object>> rows = mastery.stream().map(GetWeakConceptsTool::toRow).toList();
        return ToolResult.ok(weak + " weak concept(s) out of " + mastery.size(), Map.of("concepts", rows));
    }

    private static Map<String, Object> toRow(ConceptMastery m) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("conceptId", m.conceptId());
        row.put("name", m.conceptName());
        row.put("status", m.statusLabel());
        row.put("score", m.insufficientData() ? null : Math.round(m.score()));
        row.put("evidence", m.evidenceCount());
        row.put("trend", m.trend().name().toLowerCase());
        return row;
    }
}
