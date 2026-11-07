package studybuddy.agent;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import studybuddy.application.LearningContentService;
import studybuddy.domain.Citation;

/** Finds the excerpts of a course's materials most relevant to a query, using the normal retrieval. */
public class SearchMaterialsTool implements AgentTool {

    private final LearningContentService contentService;

    public SearchMaterialsTool(LearningContentService contentService) {
        this.contentService = contentService;
    }

    @Override
    public String getName() {
        return "search_materials";
    }

    @Override
    public String getDescription() {
        return "Searches a course's materials and returns the most relevant excerpts with their material and page. "
                + "Use it before saying anything about course content.";
    }

    @Override
    public String getParameterSchema() {
        return """
                {"type": "object",
                 "properties": {
                   "courseId": {"type": "integer", "description": "Course id"},
                   "query": {"type": "string", "description": "What to look for"},
                   "limit": {"type": "integer", "minimum": 1, "maximum": 10, "description": "Most excerpts to return; default 5"}},
                 "required": ["courseId", "query"]}""";
    }

    @Override
    public String describe(Map<String, Object> args) {
        return "Searching materials for \"" + AgentTool.stringArg(args, "query", "") + "\"";
    }

    @Override
    public ToolResult execute(Map<String, Object> args) {
        List<Citation> excerpts = contentService.searchMaterials(AgentTool.longArg(args, "courseId"),
                AgentTool.stringArg(args, "query", ""), AgentTool.intArg(args, "limit", 5));
        List<Map<String, Object>> rows = excerpts.stream().map(SearchMaterialsTool::toRow).toList();
        return ToolResult.ok("Found " + excerpts.size() + " excerpt(s)", Map.of("excerpts", rows));
    }

    private static Map<String, Object> toRow(Citation excerpt) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("material", excerpt.materialTitle());
        row.put("pages", excerpt.pageLabel());
        row.put("text", excerpt.excerpt());
        return row;
    }
}
