package studybuddy.presentation.cli;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.Callable;

import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.ParentCommand;
import studybuddy.application.ContentGenerationException;
import studybuddy.domain.Explanation;
import studybuddy.domain.Question;
import studybuddy.domain.QuizAttempt;

/** {@code studybuddy explain --attempt 15 --question 3} (F08). */
@Command(name = "explain", description = "Explain a graded quiz answer, citing your materials.")
public class ExplainCommand implements Callable<Integer> {

    @ParentCommand
    StudyAgentCli root;

    @Option(names = "--attempt", required = true, description = "Attempt id.")
    long attemptId;

    @Option(names = "--question", required = true, description = "Question id.")
    long questionId;

    @Override
    public Integer call() {
        try {
            Explanation explanation = root.facade().explainAnswer(attemptId, questionId);
            root.printResult(toJson(explanation), toText(explanation));
            return 0;
        } catch (ContentGenerationException e) {
            String correct = correctAnswer();
            Map<String, Object> json = new LinkedHashMap<>();
            json.put("error", e.getMessage());
            json.put("correctAnswer", correct);
            root.printResult(json, "Error: " + e.getMessage() + "\nCorrect answer: " + correct);
            return 1;
        }
    }

    /** F08: if the explanation fails, the correct answer is still shown. */
    private String correctAnswer() {
        QuizAttempt attempt = root.facade().getAttempt(attemptId);
        return root.facade().getQuiz(attempt.getQuizId()).findQuestion(questionId).map(Question::correctAnswerText)
                .orElseThrow();
    }

    private static String toText(Explanation explanation) {
        StringBuilder text = new StringBuilder(explanation.text());
        if (explanation.misconception() != null) {
            text.append("\n\nLikely misconception: ").append(explanation.misconception());
        }
        text.append("\n\nSource: ").append(explanation.sourceAvailable()
                ? explanation.citation().materialTitle() + ", " + explanation.citation().pageLabel()
                : "no source available");
        return text.toString();
    }

    private static Map<String, Object> toJson(Explanation explanation) {
        Map<String, Object> json = new LinkedHashMap<>();
        json.put("explanation", explanation.text());
        json.put("misconception", explanation.misconception());
        json.put("sourceAvailable", explanation.sourceAvailable());
        if (explanation.sourceAvailable()) {
            Map<String, Object> citation = new LinkedHashMap<>();
            citation.put("material", explanation.citation().materialTitle());
            citation.put("page", explanation.citation().page());
            citation.put("endPage", explanation.citation().endPage());
            citation.put("excerpt", explanation.citation().excerpt());
            json.put("citation", citation);
        } else {
            json.put("citation", null);
        }
        return json;
    }
}
