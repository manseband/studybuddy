package studybuddy.presentation.cli;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;

import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.ParentCommand;
import studybuddy.domain.Course;

/** {@code studybuddy course create|list|delete} (F01). */
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
        return json;
    }

    @Command(name = "create", description = "Create a course, e.g. course create EECS3311 --name \"Software Design\"")
    static class Create implements Callable<Integer> {
        @ParentCommand
        CourseCommand parent;

        @Parameters(index = "0", description = "Course code (unique), e.g. EECS3311.")
        String code;

        @Option(names = "--name", description = "Full course name, e.g. Software Design.", defaultValue = "")
        String name;

        @Override
        public Integer call() {
            Course course = parent.root.facade().createCourse(code, name);
            parent.root.printResult(toJson(course), "Created course #" + course.getId() + ": " + course);
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
                text.append("No courses yet. Create one with: studybuddy course create CODE");
            }
            for (Course c : courses) {
                text.append(String.format("#%-3d %-12s %s%n", c.getId(), c.getCode(), c.getName()));
            }
            parent.root.printResult(courses.stream().map(CourseCommand::toJson).toList(), text.toString().stripTrailing());
            return 0;
        }
    }

    @Command(name = "delete", description = "Delete a course and everything in it.")
    static class Delete implements Callable<Integer> {
        @ParentCommand
        CourseCommand parent;

        @Parameters(index = "0", description = "Course code or id.")
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
                parent.root.printResult(json, "This will permanently delete course " + c.getCode() + " with "
                        + materials + " material(s) and all its quizzes, flashcards and history.\n"
                        + "Run again with --yes to confirm.");
                return 2;
            }
            parent.root.facade().deleteCourse(c.getId());
            Map<String, Object> json = new LinkedHashMap<>(toJson(c));
            json.put("deleted", true);
            parent.root.printResult(json, "Deleted course " + c.getCode());
            return 0;
        }
    }
}
