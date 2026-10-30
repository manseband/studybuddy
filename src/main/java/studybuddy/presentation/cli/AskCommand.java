package studybuddy.presentation.cli;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;

import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.ParentCommand;
import studybuddy.application.ContentGenerationException;
import studybuddy.domain.Answer;
import studybuddy.domain.Citation;
import studybuddy.domain.Course;

/** {@code studyagent ask --course EECS3311 "How is Adapter different from Facade?"} (F04). */
@Command(name = "ask", description = "Ask a question about a course; the answer cites your materials.")
public class AskCommand implements Callable<Integer> {

    private static final int PREVIEW_CHARS = 120;

    @ParentCommand
    StudyAgentCli root;

    @Option(names = "--course", required = true, description = "Course name or id.")
    String course;

    @Option(names = "--general", description = "Allow a general-knowledge answer if the materials do not cover it.")
    boolean allowGeneralKnowledge;

    @Parameters(arity = "1..*", description = "The question (quotes optional).")
    List<String> words;

    @Override
    public Integer call() {
        Course c = root.facade().findCourse(course);
        String question = String.join(" ", words);
        try {
            Answer answer = root.facade().askQuestion(c.getId(), question, allowGeneralKnowledge);
            root.printResult(toJson(question, answer), toText(answer));
            return 0;
        } catch (ContentGenerationException e) {
            Map<String, Object> json = new LinkedHashMap<>();
            json.put("error", e.getMessage());
            json.put("excerpts", e.getExcerpts().stream().map(AskCommand::citationJson).toList());
            StringBuilder text = new StringBuilder("Error: ").append(e.getMessage());
            if (!e.getExcerpts().isEmpty()) {
                text.append("\n\nRelevant excerpts from your materials:");
                e.getExcerpts().forEach(x -> text.append('\n').append(citationLine(x)));
            }
            root.printResult(json, text.toString());
            return 1;
        }
    }

    private String toText(Answer answer) {
        StringBuilder text = new StringBuilder();
        if (answer.generalKnowledge()) {
            text.append("[Not from your course materials]\n");
        }
        text.append(answer.text());
        if (!answer.grounded() && !answer.generalKnowledge()) {
            text.append("\nUse --general to allow an answer from general knowledge.");
        }
        if (!answer.citations().isEmpty()) {
            text.append("\n\nSources:");
            answer.citations().forEach(c -> text.append('\n').append(citationLine(c)));
        }
        if (answer.grounded() && answer.partiallyUnverified()) {
            text.append("\n(Partially unverified: some statements could not be matched to a provided excerpt.)");
        }
        return text.toString();
    }

    private static String citationLine(Citation c) {
        String excerpt = c.excerpt().replaceAll("\\s+", " ").strip();
        if (excerpt.length() > PREVIEW_CHARS) {
            excerpt = excerpt.substring(0, PREVIEW_CHARS) + "...";
        }
        return " [" + c.excerptNumber() + "] " + c.materialTitle() + ", " + c.pageLabel() + ": \"" + excerpt + "\"";
    }

    private static Map<String, Object> toJson(String question, Answer answer) {
        Map<String, Object> json = new LinkedHashMap<>();
        json.put("question", question);
        json.put("answer", answer.text());
        json.put("grounded", answer.grounded());
        json.put("generalKnowledge", answer.generalKnowledge());
        json.put("partiallyUnverified", answer.partiallyUnverified());
        json.put("citations", answer.citations().stream().map(AskCommand::citationJson).toList());
        return json;
    }

    private static Map<String, Object> citationJson(Citation c) {
        Map<String, Object> json = new LinkedHashMap<>();
        json.put("number", c.excerptNumber());
        json.put("materialId", c.materialId());
        json.put("material", c.materialTitle());
        json.put("page", c.page());
        json.put("endPage", c.endPage());
        json.put("excerpt", c.excerpt());
        return json;
    }
}
