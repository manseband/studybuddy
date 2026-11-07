package studybuddy.presentation.cli;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.stream.Collectors;

import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.ParentCommand;
import studybuddy.domain.ActivityRecord;
import studybuddy.domain.ActivityType;
import studybuddy.domain.Course;

/** {@code studybuddy history --course EECS3311 [--since DATE] [--until DATE] [--type TYPE]} (F10). */
@Command(name = "history", description = "List a course's study activity, newest first.")
public class HistoryCommand implements Callable<Integer> {

    @ParentCommand
    StudyAgentCli root;

    @Option(names = "--course", required = true, description = "Course name or id.")
    String course;

    @Option(names = "--since", description = "First day to include, as YYYY-MM-DD.")
    String since;

    @Option(names = "--until", description = "Last day to include, as YYYY-MM-DD.")
    String until;

    @Option(names = "--type", description = "material, question, quiz, flashcards or coach.")
    String type;

    @Override
    public Integer call() {
        Course c = root.facade().findCourse(course);
        ActivityType activityType = type == null ? null : ActivityType.fromString(type);
        List<ActivityRecord> history = root.facade().getHistory(c.getId(), date(since), date(until), activityType);
        root.printResult(Map.of("history", history.stream().map(HistoryCommand::toJson).toList()), toText(history));
        return 0;
    }

    private static LocalDate date(String value) {
        if (value == null) {
            return null;
        }
        try {
            return LocalDate.parse(value);
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException("'" + value + "' is not a date. Use YYYY-MM-DD.");
        }
    }

    private String toText(List<ActivityRecord> history) {
        if (history.isEmpty()) {
            return since == null && until == null && type == null
                    ? "No activity yet. Upload a material, ask a question or take a quiz to get started."
                    : "No activity matches this filter.";
        }
        return history.stream()
                .map(r -> String.format("%s  %-18s  %s  %s", r.dateLabel(), r.activity(), r.details(), r.result())
                        .stripTrailing())
                .collect(Collectors.joining("\n"));
    }

    private static Map<String, Object> toJson(ActivityRecord r) {
        Map<String, Object> json = new LinkedHashMap<>();
        json.put("occurredAt", r.occurredAt());
        json.put("type", r.type().name().toLowerCase());
        json.put("activity", r.activity());
        json.put("details", r.details());
        json.put("result", r.result());
        return json;
    }
}
