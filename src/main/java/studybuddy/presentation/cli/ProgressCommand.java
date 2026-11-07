package studybuddy.presentation.cli;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.Callable;

import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.ParentCommand;
import studybuddy.domain.Course;
import studybuddy.domain.ProgressReport;
import studybuddy.domain.ReportFormat;

/** {@code studybuddy progress --course EECS3311} and {@code progress export} (F10). */
@Command(name = "progress", description = "Show a course's average score, study streak and concept mastery.",
        subcommands = ProgressCommand.Export.class)
public class ProgressCommand implements Callable<Integer> {

    @ParentCommand
    StudyAgentCli root;

    /** Not marked required, because picocli would then demand it before the export subcommand too. */
    @Option(names = "--course", description = "Course name or id.")
    String course;

    @Override
    public Integer call() {
        if (course == null) {
            throw new IllegalArgumentException("Choose a course with --course.");
        }
        Course c = root.facade().findCourse(course);
        ProgressReport report = root.facade().getProgress(c.getId());

        Map<String, Object> json = new LinkedHashMap<>();
        json.put("course", report.courseName());
        json.put("gradedQuizzes", report.gradedQuizzes());
        json.put("averageScore", report.gradedQuizzes() == 0 ? null : report.averageScore());
        json.put("currentStreak", report.currentStreak());
        json.put("concepts", report.mastery().stream().map(WeakConceptsCommand::toJson).toList());
        String text = "Average score: " + report.averageScoreText() + "\nCurrent streak: " + report.streakText()
                + "\n\n" + WeakConceptsCommand.toText(report.mastery());
        root.printResult(json, text);
        return 0;
    }

    @Command(name = "export", description = "Save the progress report as a Markdown or CSV file.")
    static class Export implements Callable<Integer> {

        @ParentCommand
        ProgressCommand parent;

        @Option(names = "--course", required = true, description = "Course name or id.")
        String course;

        @Option(names = "--format", required = true, description = "markdown or csv.")
        String format;

        @Option(names = "--out", required = true, description = "The file to write. An existing file is replaced.")
        Path out;

        @Override
        public Integer call() {
            Course c = parent.root.facade().findCourse(course);
            Path file = parent.root.facade().exportReport(c.getId(), ReportFormat.fromString(format), out);
            parent.root.printResult(Map.of("file", file.toAbsolutePath().toString()), "Report saved to " + file);
            return 0;
        }
    }
}
