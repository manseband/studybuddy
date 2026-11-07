package studybuddy.agent;

import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import studybuddy.application.QuizService;
import studybuddy.domain.CreatedItem;
import studybuddy.domain.Difficulty;
import studybuddy.domain.Question;
import studybuddy.domain.QuestionType;
import studybuddy.domain.Quiz;
import studybuddy.domain.QuizRequest;

/** Creates a quiz with the normal generation and validation. The result never includes the answers. */
public class GenerateQuizTool implements AgentTool {

    private static final int DEFAULT_COUNT = 5;

    private final QuizService quizService;

    public GenerateQuizTool(QuizService quizService) {
        this.quizService = quizService;
    }

    @Override
    public String getName() {
        return "generate_quiz";
    }

    @Override
    public String getDescription() {
        return "Creates and saves a new quiz from a course's materials or concepts. Leave out conceptIds and "
                + "materialIds to cover the whole course. The student takes it in the Quizzes tab; you never see "
                + "the answers.";
    }

    @Override
    public String getParameterSchema() {
        return """
                {"type": "object",
                 "properties": {
                   "courseId": {"type": "integer", "description": "Course id"},
                   "count": {"type": "integer", "minimum": %d, "maximum": %d, "description": "Number of questions; default %d"},
                   "difficulty": {"type": "string", "enum": ["easy", "medium", "hard"], "description": "Default medium"},
                   "types": {"type": "array", "items": {"type": "string", "enum": ["mcq", "tf", "short"]},
                             "description": "Question types; default all three"},
                   "conceptIds": {"type": "array", "items": {"type": "integer"}, "description": "Only these concepts"},
                   "materialIds": {"type": "array", "items": {"type": "integer"}, "description": "Only these materials"}},
                 "required": ["courseId"]}""".formatted(QuizRequest.MIN_QUESTIONS, QuizRequest.MAX_QUESTIONS, DEFAULT_COUNT);
    }

    @Override
    public String describe(Map<String, Object> args) {
        return "Generating " + AgentTool.intArg(args, "count", DEFAULT_COUNT) + "-question quiz";
    }

    @Override
    public ToolResult execute(Map<String, Object> args) {
        int count = AgentTool.intArg(args, "count", DEFAULT_COUNT);
        Quiz quiz = quizService.generateQuiz(new QuizRequest(AgentTool.longArg(args, "courseId"),
                AgentTool.idsArg(args, "materialIds"), AgentTool.idsArg(args, "conceptIds"), count, types(args),
                Difficulty.fromString(AgentTool.stringArg(args, "difficulty", "medium")), false));

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("quizId", quiz.getId());
        data.put("requested", count);
        data.put("created", quiz.size());
        data.put("questions", quiz.getQuestions().stream().map(Question::getStem).toList());
        String label = "Quiz #" + quiz.getId() + " (" + quiz.size() + " questions)";
        return ToolResult.ok("Created " + label, data, List.of(new CreatedItem(CreatedItem.Type.QUIZ, quiz.getId(), label)));
    }

    private static Set<QuestionType> types(Map<String, Object> args) {
        List<String> codes = AgentTool.stringsArg(args, "types");
        if (codes.isEmpty()) {
            return EnumSet.allOf(QuestionType.class);
        }
        Set<QuestionType> types = EnumSet.noneOf(QuestionType.class);
        codes.forEach(code -> types.add(QuestionType.fromCode(code)));
        return types;
    }
}
