package studybuddy.presentation.cli;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;

import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.ParentCommand;
import studybuddy.domain.Course;

/** {@code studyagent course create|list|delete} (F01). */
@Command(name = "course", description = "Create, list and delete courses.",
        subcommands = {CourseCommand.Create.class, CourseCommand.ListCourses.class, CourseCommand.Delete.class})
public class CourseCommand {

    @ParentCommand
    StudyAgentCli root;

    static Map<String, Object> toJson(Course course) {
        Map<String, Object> json = new LinkedHashMap<>();
        json.put("id", course.getId());
        json.put("name", course.getName());
        json.put("code", course.getCode());
        json.put("examDate", course.getExamDate());
        return json;
    }

    @Command(name = "create", description = "Create a course, e.g. course create \"EECS3311\" --exam 2026-12-10")
    static class Create implements Callable<Integer> {
        @ParentCommand
        CourseCommand parent;

        @Parameters(index = "0", description = "Course name (unique).")
        String name;

        @Option(names = "--code", description = "Course code or description.", defaultValue = "")
        String code;

        @Option(names = "--exam", description = "Exam date, yyyy-mm-dd.")
        LocalDate examDate;

        @Override
        public Integer call() {
            Course course = parent.root.facade().createCourse(name, code, examDate);
            parent.root.printResult(toJson(course), "Created course #" + course.getId() + ": " + course
                    + (examDate == null ? "" : ", exam on " + examDate));
            return 0;
        }
    }

    @Command(name = "list", description = "List all courses.")
    static class ListCourses implements Callable<Integer> {
        @ParentCommand
        CourseCommand parent;

        @Override
        public Integer call() {
            List<Course> courses = parent.root.facade().listCourses();
            StringBuilder text = new StringBuilder();
            if (courses.isEmpty()) {
                text.append("No courses yet. Create one with: studyagent course create \"NAME\"");
            }
            for (Course c : courses) {
                text.append(String.format("#%-3d %-20s %-25s %s%n", c.getId(), c.getName(), c.getCode(),
                        c.getExamDate() == null ? "" : "exam " + c.getExamDate()));
            }
            parent.root.printResult(courses.stream().map(CourseCommand::toJson).toList(), text.toString().stripTrailing());
            return 0;
        }
    }

    @Command(name = "delete", description = "Delete a course and everything in it.")
    static class Delete implements Callable<Integer> {
        @ParentCommand
        CourseCommand parent;

        @Parameters(index = "0", description = "Course name or id.")
        String course;

        @Option(names = "--yes", description = "Confirm the deletion.")
        boolean confirmed;

        @Override
        public Integer call() {
            Course c = parent.root.facade().findCourse(course);
            int materials = parent.root.facade().listMaterials(c.getId()).size();
            if (!confirmed) {
                Map<String, Object> json = new LinkedHashMap<>(toJson(c));
                json.put("deleted", false);
                json.put("materials", materials);
                parent.root.printResult(json, "This will permanently delete course " + c.getName() + " with "
                        + materials + " material(s) and all its quizzes, flashcards, plans and history.\n"
                        + "Run again with --yes to confirm.");
                return 2;
            }
            parent.root.facade().deleteCourse(c.getId());
            Map<String, Object> json = new LinkedHashMap<>(toJson(c));
            json.put("deleted", true);
            parent.root.printResult(json, "Deleted course " + c.getName());
            return 0;
        }
    }
}
