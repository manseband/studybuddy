package studybuddy.presentation.cli;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.stream.Collectors;

import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.ParentCommand;
import studybuddy.domain.Concept;
import studybuddy.domain.Course;
import studybuddy.domain.Material;

/** {@code studyagent concepts extract|list|show|edit|merge|delete} (F03). */
@Command(name = "concepts", description = "Extract and manage the key concepts of a course.",
        subcommands = {ConceptsCommand.Extract.class, ConceptsCommand.ListConcepts.class, ConceptsCommand.Show.class,
                ConceptsCommand.Edit.class, ConceptsCommand.Merge.class, ConceptsCommand.Delete.class})
public class ConceptsCommand {

    @ParentCommand
    StudyAgentCli root;

    static Map<String, Object> toJson(Concept c) {
        Map<String, Object> json = new LinkedHashMap<>();
        json.put("id", c.getId());
        json.put("name", c.getName());
        json.put("definition", c.getDefinition());
        json.put("importance", c.getImportance());
        json.put("pages", c.getSourcePages());
        json.put("prerequisites", c.getPrerequisites().stream().map(Concept::getName).toList());
        return json;
    }

    /**
     * Two lines per concept, e.g.
     * <pre>
     * #3     ****  Observer pattern             p. 3,4   needs: Interface
     *        Subjects notify registered observers when their state changes.
     * </pre>
     * Importance is shown as 1-5 stars; unsaved concepts show "new" instead of an id.
     */
    static String table(List<Concept> concepts) {
        StringBuilder text = new StringBuilder();
        for (Concept c : concepts) {
            String id = c.getId() == 0 ? "new" : "#" + c.getId();
            String stars = "*".repeat(c.getImportance());
            String pages = c.getSourcePages().stream().map(String::valueOf).collect(Collectors.joining(","));
            String needs = c.getPrerequisites().stream().map(Concept::getName).collect(Collectors.joining(", "));
            text.append(String.format("%-6s %-5s %-28s p. %-8s", id, stars, c.getName(), pages));
            if (!needs.isEmpty()) {
                text.append(" needs: ").append(needs);
            }
            text.append("\n       ").append(c.getDefinition()).append('\n');
        }
        return text.toString().stripTrailing();
    }

    @Command(name = "extract", description = "Find the key concepts in a material and save them to the course.")
    static class Extract implements Callable<Integer> {
        @ParentCommand
        ConceptsCommand parent;

        @Option(names = "--material", required = true, description = "Material id.")
        long materialId;

        @Option(names = "--preview", description = "Only show the concepts; do not save them.")
        boolean preview;

        @Override
        public Integer call() {
            List<Concept> concepts = parent.root.facade().extractConcepts(materialId);
            if (concepts.isEmpty()) {
                parent.root.printResult(List.of(), "No concepts were found. The material may be too short.");
                return 0;
            }
            if (!preview) {
                concepts = parent.root.facade().saveConcepts(materialId, concepts);
            }
            parent.root.printResult(concepts.stream().map(ConceptsCommand::toJson).toList(), table(concepts)
                    + "\n\n" + concepts.size() + " concept(s) " + (preview ? "found (preview, not saved)." : "saved."));
            return 0;
        }
    }

    @Command(name = "list", description = "List the concepts of a course.")
    static class ListConcepts implements Callable<Integer> {
        @ParentCommand
        ConceptsCommand parent;

        @Option(names = "--course", required = true, description = "Course name or id.")
        String course;

        @Override
        public Integer call() {
            Course c = parent.root.facade().findCourse(course);
            List<Concept> concepts = parent.root.facade().listConcepts(c.getId());
            parent.root.printResult(concepts.stream().map(ConceptsCommand::toJson).toList(), concepts.isEmpty()
                    ? "No concepts yet. Extract them with: studyagent concepts extract --material ID"
                    : table(concepts));
            return 0;
        }
    }

    @Command(name = "show", description = "Show a concept with its prerequisites and materials.")
    static class Show implements Callable<Integer> {
        @ParentCommand
        ConceptsCommand parent;

        @Parameters(index = "0", description = "Concept id.")
        long id;

        @Override
        public Integer call() {
            Concept c = parent.root.facade().getConcept(id);
            List<Material> materials = parent.root.facade().getConceptMaterials(id);
            Map<String, Object> json = toJson(c);
            json.put("materials", materials.stream().map(Material::getTitle).toList());
            parent.root.printResult(json, table(List.of(c)) + "\n      appears in: "
                    + (materials.isEmpty() ? "-" : materials.stream().map(Material::getTitle)
                            .collect(Collectors.joining(", "))));
            return 0;
        }
    }

    @Command(name = "edit", description = "Change a concept's name, definition or importance.")
    static class Edit implements Callable<Integer> {
        @ParentCommand
        ConceptsCommand parent;

        @Parameters(index = "0", description = "Concept id.")
        long id;

        @Option(names = "--name", description = "New name.")
        String name;

        @Option(names = "--definition", description = "New definition.")
        String definition;

        @Option(names = "--importance", description = "New importance, 1-5.")
        Integer importance;

        @Override
        public Integer call() {
            Concept c = parent.root.facade().updateConcept(id, name, definition, importance);
            parent.root.printResult(toJson(c), "Updated:\n" + table(List.of(c)));
            return 0;
        }
    }

    @Command(name = "merge", description = "Merge one concept into another (tags and links move to the second).")
    static class Merge implements Callable<Integer> {
        @ParentCommand
        ConceptsCommand parent;

        @Parameters(index = "0", description = "Concept id to merge away.")
        long fromId;

        @Parameters(index = "1", description = "Concept id to keep.")
        long intoId;

        @Override
        public Integer call() {
            Concept kept = parent.root.facade().mergeConcepts(fromId, intoId);
            parent.root.printResult(toJson(kept), "Merged #" + fromId + " into:\n" + table(List.of(kept)));
            return 0;
        }
    }

    @Command(name = "delete", description = "Delete a concept; its questions and flashcards become untagged.")
    static class Delete implements Callable<Integer> {
        @ParentCommand
        ConceptsCommand parent;

        @Parameters(index = "0", description = "Concept id.")
        long id;

        @Override
        public Integer call() {
            parent.root.facade().deleteConcept(id);
            Map<String, Object> json = new LinkedHashMap<>();
            json.put("id", id);
            json.put("deleted", true);
            parent.root.printResult(json, "Deleted concept #" + id + ". Questions and flashcards tagged with it are "
                    + "now untagged.");
            return 0;
        }
    }
}
