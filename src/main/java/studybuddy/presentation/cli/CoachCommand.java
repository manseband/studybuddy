package studybuddy.presentation.cli;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.stream.Collectors;

import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.ParentCommand;
import studybuddy.domain.AgentResponse;
import studybuddy.domain.CreatedItem;
import studybuddy.domain.TraceStep;

/** {@code studybuddy coach [--course C]} starts a conversation; {@code --once "message"} sends one message (F12). */
@Command(name = "coach", description = "Talk to the Study Coach, which carries out study goals step by step.")
public class CoachCommand implements Callable<Integer> {

    @ParentCommand
    StudyAgentCli root;

    @Option(names = "--course", description = "Course name or id. Without it, the coach asks if there are several.")
    String course;

    @Option(names = "--once", description = "Send this one message and exit.")
    String once;

    @Override
    public Integer call() throws IOException {
        Long courseId = course == null ? null : root.facade().findCourse(course).getId();
        if (once != null) {
            return ask(courseId, once) == AgentResponse.Status.FAILED ? 1 : 0;
        }
        root.prompt("Study Coach. Type your goal, or \"exit\" to finish.\n");
        while (true) {
            root.prompt("\nYou: ");
            String line = root.readLine();
            if (line == null || line.strip().equalsIgnoreCase("exit")) {
                return 0;
            }
            if (!line.isBlank()) {
                ask(courseId, line);
            }
        }
    }

    private AgentResponse.Status ask(Long courseId, String message) {
        AgentResponse response = root.facade().askCoach(courseId, message, this::showStep);
        Map<String, Object> json = new LinkedHashMap<>();
        json.put("response", response.text());
        json.put("status", response.status().name());
        json.put("steps", response.steps());
        json.put("created", response.created());
        root.printResult(json, toText(response));
        return response.status();
    }

    /** Shows each step as it starts, and any failure. */
    private void showStep(TraceStep step) {
        if (step.status() == TraceStep.Status.RUNNING) {
            root.prompt("  ... " + step.description() + "\n");
        } else if (step.status() == TraceStep.Status.FAILED) {
            root.prompt("  ... failed: " + step.result() + "\n");
        }
    }

    private static String toText(AgentResponse response) {
        String text = "Coach: " + response.text();
        if (!response.created().isEmpty()) {
            text += "\nCreated: " + response.created().stream().map(CreatedItem::label)
                    .collect(Collectors.joining(", "));
        }
        return text;
    }
}
